/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.common.buffer.impl;

import java.io.*;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.teiid.common.buffer.*;
import org.teiid.common.buffer.BatchManager.ManagedBatch;
import org.teiid.core.TeiidComponentException;
import org.teiid.core.TeiidRuntimeException;
import org.teiid.core.types.DataTypeManager;
import org.teiid.core.util.Assertion;
import org.teiid.dqp.internal.process.DQPConfiguration;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.logging.MessageLevel;
import org.teiid.query.QueryPlugin;
import org.teiid.query.ReplicatedObject;
import org.teiid.query.processor.relational.ListNestedSortComparator;
import org.teiid.query.sql.symbol.ElementSymbol;
import org.teiid.query.sql.symbol.Expression;


/**
 * <p>Default implementation of BufferManager.</p>
 * Responsible for creating/tracking TupleBuffers and providing access to the StorageManager.
 * </p>
 * The buffering strategy attempts to purge batches from the least recently used TupleBuffer
 * from before (which wraps around circularly) the last used batch.  This attempts to compensate 
 * for our tendency to read buffers in a forward manner.  If our processing algorithms are changed 
 * to use alternating ascending/descending access, then the buffering approach could be replaced 
 * with a simple LRU.
 * 
 * TODO: allow for cached stores to use lru - (result set/mat view)
 * TODO: account for row/content based sizing (difficult given value sharing)
 * TODO: account for memory based lobs (it would be nice if the approximate buffer size matched at 100kB)
 * TODO: add detection of pinned batches to prevent unnecessary purging of non-persistent batches
 *       - this is not necessary for already persistent batches, since we hold a weak reference
 */
public class BufferManagerImpl implements BufferManager, StorageManager, ReplicatedObject {
	
	private static final int IO_BUFFER_SIZE = 1 << 14;
	private static final int COMPACTION_THRESHOLD = 1 << 25; //start checking at 32 megs
	
	private final class CleanupHook implements org.teiid.common.buffer.BatchManager.CleanupHook {
		
		private long id;
		private int beginRow;
		private WeakReference<BatchManagerImpl> ref;
		
		CleanupHook(long id, int beginRow, BatchManagerImpl batchManager) {
			this.id = id;
			this.beginRow = beginRow;
			this.ref = new WeakReference<BatchManagerImpl>(batchManager);
		}
		
		public void cleanup() {
			BatchManagerImpl batchManager = ref.get();
			if (batchManager == null) {
				return;
			}
			cleanupManagedBatch(batchManager, beginRow, id);
		}
		
	}
	
	private final class BatchManagerImpl implements BatchManager {
		private final String id;
		private volatile FileStore store;
		private Map<Long, long[]> physicalMapping = new ConcurrentHashMap<Long, long[]>();
		private ReadWriteLock compactionLock = new ReentrantReadWriteLock();
		private AtomicLong unusedSpace = new AtomicLong();
		private int[] lobIndexes;
		private SizeUtility sizeUtility;
		
		private BatchManagerImpl(String newID, int[] lobIndexes) {
			this.id = newID;
			this.store = createFileStore(id);
			this.store.setCleanupReference(this);
			this.lobIndexes = lobIndexes;
			this.sizeUtility = new SizeUtility();
		}
		
		public FileStore createStorage(String prefix) {
			return createFileStore(id+prefix);
		}

		@Override
		public ManagedBatch createManagedBatch(TupleBatch batch, boolean softCache)
				throws TeiidComponentException {
			ManagedBatchImpl mbi = new ManagedBatchImpl(batch, this, softCache);
			mbi.addToCache(false);
			persistBatchReferences();
			return mbi;
		}
		
		private boolean shouldCompact(long offset) {
			return offset > COMPACTION_THRESHOLD && unusedSpace.get() * 4 > offset * 3;
		}
		
		private long getOffset() throws TeiidComponentException {
			long offset = store.getLength();
			if (!shouldCompact(offset)) {
				return offset;
			}
			try {
				this.compactionLock.writeLock().lock();
				offset = store.getLength();
				//retest the condition to ensure that compaction is still needed
				if (!shouldCompact(offset)) {
					return offset;
				}
				FileStore newStore = createFileStore(id);
				newStore.setCleanupReference(this);
				byte[] buffer = new byte[IO_BUFFER_SIZE];
				List<long[]> values = new ArrayList<long[]>(physicalMapping.values());
				Collections.sort(values, new Comparator<long[]>() {
					@Override
					public int compare(long[] o1, long[] o2) {
						return Long.signum(o1[0] - o2[0]);
					}
				});
				for (long[] info : values) {
					long oldOffset = info[0];
					info[0] = newStore.getLength();
					int size = (int)info[1];
					while (size > 0) {
						int toWrite = Math.min(IO_BUFFER_SIZE, size);
						store.readFully(oldOffset, buffer, 0, toWrite);
						newStore.write(buffer, 0, toWrite);
						size -= toWrite;
					}
				}
				store.remove();
				store = newStore;
				long oldOffset = offset;
				offset = store.getLength();
				if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.TRACE)) {
					LogManager.logTrace(LogConstants.CTX_BUFFER_MGR, "Compacted store", id, "pre-size", oldOffset, "post-size", offset); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return offset;
			} finally {
				this.compactionLock.writeLock().unlock();
			}
		}

		@Override
		public void remove() {
			this.store.remove();
		}
	}

	/**
	 * Holder for active batches
	 */
	private class TupleBufferInfo {
		TreeMap<Integer, ManagedBatchImpl> batches = new TreeMap<Integer, ManagedBatchImpl>();
		Integer lastUsed = null;
		
		ManagedBatchImpl removeBatch(int row) {
			ManagedBatchImpl result = batches.remove(row);
			if (result != null) {
				activeBatchKB -= result.sizeEstimate;
			}
			return result;
		}
	}
	
	private final class ManagedBatchImpl implements ManagedBatch {
		private boolean persistent;
		private boolean softCache;
		private volatile TupleBatch activeBatch;
		private volatile Reference<TupleBatch> batchReference;
		private int beginRow;
		private BatchManagerImpl batchManager;
		private long id;
		private LobManager lobManager;
		private int sizeEstimate;
		
		public ManagedBatchImpl(TupleBatch batch, BatchManagerImpl manager, boolean softCache) {
			this.softCache = softCache;
			id = batchAdded.incrementAndGet();
			this.activeBatch = batch;
			this.beginRow = batch.getBeginRow();
			this.batchManager = manager;
			if (this.batchManager.lobIndexes != null) {
				this.lobManager = new LobManager();
			}
			sizeEstimate = (int) Math.max(1, manager.sizeUtility.getBatchSize(batch) / 1024);
			if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.TRACE)) {
				LogManager.logTrace(LogConstants.CTX_BUFFER_MGR, "Add batch to BufferManager", id, "with size estimate", sizeEstimate); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		
		@Override
		public void setPrefersMemory(boolean prefers) {
			this.softCache = prefers;
			//TODO: could recreate the reference
		}

		private void addToCache(boolean update) {
			synchronized (activeBatches) {
				TupleBatch batch = this.activeBatch;
				if (batch == null) {
					return; //already removed
				}
				activeBatchKB += sizeEstimate;
				TupleBufferInfo tbi = null;
				if (update) {
					tbi = activeBatches.remove(batchManager.id);
				} else {
					tbi = activeBatches.get(batchManager.id);
				}
				if (tbi == null) {
					tbi = new TupleBufferInfo();
					update = true;
				} 
				if (update) {
					activeBatches.put(batchManager.id, tbi);
				}
				Assertion.isNull(tbi.batches.put(this.beginRow, this));
			}
		}

		@Override
		public TupleBatch getBatch(boolean cache, String[] types) throws TeiidComponentException {
			long reads = readAttempts.incrementAndGet();
			if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.TRACE)) {
				LogManager.logTrace(LogConstants.CTX_BUFFER_MGR, batchManager.id, "getting batch", reads, "reference hits", referenceHit.get()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			synchronized (activeBatches) {
				TupleBufferInfo tbi = activeBatches.remove(batchManager.id);
				if (tbi != null) { 
					boolean put = true;
					if (!cache) {
						tbi.removeBatch(this.beginRow);
						if (tbi.batches.isEmpty()) {
							put = false;
						}
					}
					if (put) {
						tbi.lastUsed = this.beginRow;
						activeBatches.put(batchManager.id, tbi);
					}
				}
			}
			persistBatchReferences();
			synchronized (this) {
				TupleBatch batch = this.activeBatch;
				if (batch != null){
					return batch;
				}
				Reference<TupleBatch> ref = this.batchReference;
				this.batchReference = null;
				if (ref != null) {
					batch = ref.get();
					if (batch != null) {
						if (cache) {
							this.activeBatch = batch;
				        	addToCache(true);
						} 
						referenceHit.getAndIncrement();
						return batch;
					}
				}
				long count = readCount.incrementAndGet();
				if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.TRACE)) {
					LogManager.logDetail(LogConstants.CTX_BUFFER_MGR, batchManager.id, id, "reading batch from disk, total reads:", count); //$NON-NLS-1$
				}
				try {
					this.batchManager.compactionLock.readLock().lock();
					long[] info = batchManager.physicalMapping.get(this.id);
					Assertion.isNotNull(info, "Invalid batch " + id); //$NON-NLS-1$
					ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(batchManager.store.createInputStream(info[0]), IO_BUFFER_SIZE));
		            batch = new TupleBatch();
		            batch.setDataTypes(types);
		            batch.readExternal(ois);
		            batch.setRowOffset(this.beginRow);
			        batch.setDataTypes(null);
					if (lobManager != null) {
						for (List<?> tuple : batch.getTuples()) {
							lobManager.updateReferences(batchManager.lobIndexes, tuple);
						}
					}
			        if (cache) {
			        	this.activeBatch = batch;
			        	addToCache(true);
			        }
					return batch;
		        } catch(IOException e) {
		        	throw new TeiidComponentException(e, QueryPlugin.Util.getString("FileStoreageManager.error_reading", batchManager.id)); //$NON-NLS-1$
		        } catch (ClassNotFoundException e) {
		        	throw new TeiidComponentException(e, QueryPlugin.Util.getString("FileStoreageManager.error_reading", batchManager.id)); //$NON-NLS-1$
		        } finally {
		        	this.batchManager.compactionLock.readLock().unlock();
		        }
			}
		}

		public synchronized void persist() throws TeiidComponentException {
			boolean lockheld = false;
            try {
				TupleBatch batch = activeBatch;
				if (batch != null) {
					if (!persistent) {
						long count = writeCount.incrementAndGet();
						if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.DETAIL)) {
							LogManager.logDetail(LogConstants.CTX_BUFFER_MGR, batchManager.id, id, "writing batch to disk, total writes: ", count); //$NON-NLS-1$
						}
						long offset = 0;
						if (lobManager != null) {
							for (List<?> tuple : batch.getTuples()) {
								lobManager.updateReferences(batchManager.lobIndexes, tuple);
							}
						}
						synchronized (batchManager.store) {
							offset = batchManager.getOffset();
							OutputStream fsos = new BufferedOutputStream(batchManager.store.createOutputStream(), IO_BUFFER_SIZE);
				            ObjectOutputStream oos = new ObjectOutputStream(fsos);
				            batch.writeExternal(oos);
				            oos.close();
				            long size = batchManager.store.getLength() - offset;
				            long[] info = new long[] {offset, size};
				            batchManager.physicalMapping.put(this.id, info);
						}
						if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.TRACE)) {
							LogManager.logTrace(LogConstants.CTX_BUFFER_MGR, batchManager.id, id, "batch written starting at:", offset); //$NON-NLS-1$
						}
					}
					if (softCache) {
						this.batchReference = new SoftReference<TupleBatch>(batch);
					} else if (useWeakReferences) {
						this.batchReference = new WeakReference<TupleBatch>(batch);
					}
				}
			} catch (IOException e) {
				throw new TeiidComponentException(e);
			} catch (Throwable e) {
				throw new TeiidComponentException(e);
			} finally {
				persistent = true;
				activeBatch = null;
				if (lockheld) {
					this.batchManager.compactionLock.writeLock().unlock();
				}
			}
		}

		public void remove() {
			cleanupManagedBatch(batchManager, beginRow, id);
		}
				
		@Override
		public CleanupHook getCleanupHook() {
			return new CleanupHook(id, beginRow, batchManager);
		}
		
		@Override
		public String toString() {
			return "ManagedBatch " + batchManager.id + " " + this.beginRow + " " + activeBatch; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}
	
	// Configuration 
    private int connectorBatchSize = BufferManager.DEFAULT_CONNECTOR_BATCH_SIZE;
    private int processorBatchSize = BufferManager.DEFAULT_PROCESSOR_BATCH_SIZE;
    //set to acceptable defaults for testing
    private int maxProcessingKB = 1 << 11; 
    private Integer maxProcessingKBOrig;
    private int maxReserveKB = 1 << 25;
    private volatile int reserveBatchKB;
    private int maxActivePlans = DQPConfiguration.DEFAULT_MAX_ACTIVE_PLANS; //used as a hint to set the reserveBatchKB
    private boolean useWeakReferences = true;

    private ReentrantLock lock = new ReentrantLock(true);
    private Condition batchesFreed = lock.newCondition();
    
    private volatile int activeBatchKB = 0;
    private Map<String, TupleBufferInfo> activeBatches = new LinkedHashMap<String, TupleBufferInfo>();
	private Map<String, TupleReference> tupleBufferMap = new ConcurrentHashMap<String, TupleReference>();
	private ReferenceQueue<TupleBuffer> tupleBufferQueue = new ReferenceQueue<TupleBuffer>();
    
    private StorageManager diskMgr;

    private AtomicLong tsId = new AtomicLong();
    private AtomicLong batchAdded = new AtomicLong();
    private AtomicLong readCount = new AtomicLong();
	private AtomicLong writeCount = new AtomicLong();
	private AtomicLong readAttempts = new AtomicLong();
	private AtomicLong referenceHit = new AtomicLong();
	
	public long getBatchesAdded() {
		return batchAdded.get();
	}
	
	public long getReadCount() {
		return readCount.get();
	}
	
	public long getWriteCount() {
		return writeCount.get();
	}
	
	public long getReadAttempts() {
		return readAttempts.get();
	}
	
	@Override
    public int getMaxProcessingKB() {
		return maxProcessingKB;
	}
    
    /**
     * Get processor batch size
     * @return Number of rows in a processor batch
     */
    @Override
    public int getProcessorBatchSize() {        
        return this.processorBatchSize;
    }

    /**
     * Get connector batch size
     * @return Number of rows in a connector batch
     */
    @Override
    public int getConnectorBatchSize() {
        return this.connectorBatchSize;
    }
    
    public void setConnectorBatchSize(int connectorBatchSize) {
        this.connectorBatchSize = connectorBatchSize;
    } 

    public void setProcessorBatchSize(int processorBatchSize) {
        this.processorBatchSize = processorBatchSize;
    } 

    /**
     * Add a storage manager to this buffer manager, order is unimportant
     * @param storageManager Storage manager to add
     */
    public void setStorageManager(StorageManager storageManager) {
    	Assertion.isNotNull(storageManager);
    	Assertion.isNull(diskMgr);
        this.diskMgr = storageManager;
    }
    
    public StorageManager getStorageManager() {
		return diskMgr;
	}
    
    @Override
    public TupleBuffer createTupleBuffer(final List elements, String groupName,
    		TupleSourceType tupleSourceType) {
    	final String newID = String.valueOf(this.tsId.getAndIncrement());
    	int[] lobIndexes = LobManager.getLobIndexes(elements);
    	BatchManager batchManager = new BatchManagerImpl(newID, lobIndexes);
        TupleBuffer tupleBuffer = new TupleBuffer(batchManager, newID, elements, lobIndexes, getProcessorBatchSize());
        if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.DETAIL)) {
        	LogManager.logDetail(LogConstants.CTX_BUFFER_MGR, "Creating TupleBuffer:", newID, elements, Arrays.toString(tupleBuffer.getTypes()), "of type", tupleSourceType); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return tupleBuffer;
    }
    
    private void cleanupManagedBatch(BatchManagerImpl batchManager, int beginRow, long id) {
		synchronized (activeBatches) {
			TupleBufferInfo tbi = activeBatches.get(batchManager.id);
			if (tbi != null && tbi.removeBatch(beginRow) != null) {
				if (tbi.batches.isEmpty()) {
					activeBatches.remove(batchManager.id);
				}
			}
		}
		long[] info = batchManager.physicalMapping.remove(id);
		if (info != null) {
			batchManager.unusedSpace.addAndGet(info[1]); 
		}
    }
    
    public STree createSTree(final List elements, String groupName, int keyLength) {
    	String newID = String.valueOf(this.tsId.getAndIncrement());
    	int[] lobIndexes = LobManager.getLobIndexes(elements);
    	BatchManager bm = new BatchManagerImpl(newID, lobIndexes);
    	BatchManager keyManager = new BatchManagerImpl(String.valueOf(this.tsId.getAndIncrement()), null);
    	int[] compareIndexes = new int[keyLength];
    	for (int i = 1; i < compareIndexes.length; i++) {
			compareIndexes[i] = i;
		}
    	if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.DETAIL)) {
    		LogManager.logDetail(LogConstants.CTX_BUFFER_MGR, "Creating STree:", newID); //$NON-NLS-1$
    	}
    	return new STree(keyManager, bm, new ListNestedSortComparator(compareIndexes), getProcessorBatchSize(), keyLength, TupleBuffer.getTypeNames(elements));
    }

    @Override
    public FileStore createFileStore(String name) {
    	if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.DETAIL)) {
    		LogManager.logDetail(LogConstants.CTX_BUFFER_MGR, "Creating FileStore:", name); //$NON-NLS-1$
    	}
    	return this.diskMgr.createFileStore(name);
    }
        
    public void setMaxActivePlans(int maxActivePlans) {
		this.maxActivePlans = maxActivePlans;
	}
    
    public void setMaxProcessingKB(int maxProcessingKB) {
		this.maxProcessingKB = maxProcessingKB;
	}
    
    public void setMaxReserveKB(int maxReserveBatchKB) {
		this.maxReserveKB = maxReserveBatchKB;
	}
    
	@Override
	public void initialize() throws TeiidComponentException {
		int maxMemory = (int)Math.min(Runtime.getRuntime().maxMemory() / 1024, Integer.MAX_VALUE);
		maxMemory -= 300 * 1024; //assume 300 megs of overhead for the AS/system stuff
		if (maxReserveKB < 0) {
			this.maxReserveKB = 0;
			int one_gig = 1024 * 1024;
			if (maxMemory > one_gig) {
				//assume 75% of the memory over the first gig
				this.maxReserveKB += (int)Math.max(0, (maxMemory - one_gig) * .75);
			}
			this.maxReserveKB += Math.max(0, Math.min(one_gig, maxMemory) * .5);
    	}
		this.reserveBatchKB = this.maxReserveKB;
		if (this.maxProcessingKBOrig == null) {
			//store the config value so that we can be reinitialized (this is not a clean approach)
			this.maxProcessingKBOrig = this.maxProcessingKB;
		}
		if (this.maxProcessingKBOrig < 0) {
			this.maxProcessingKB = Math.max(Math.min(8 * processorBatchSize, Integer.MAX_VALUE), (int)(.1 * maxMemory)/maxActivePlans);
		} 
	}
	
    @Override
    public void releaseBuffers(int count) {
    	if (count < 1) {
    		return;
    	}
    	if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.TRACE)) {
    		LogManager.logTrace(LogConstants.CTX_BUFFER_MGR, "Releasing buffer space", count); //$NON-NLS-1$
    	}
    	lock.lock();
    	try {
	    	this.reserveBatchKB += count;
	    	batchesFreed.signalAll();
    	} finally {
    		lock.unlock();
    	}
    }	
    
    @Override
    public int reserveBuffers(int count, BufferReserveMode mode) {
    	if (LogManager.isMessageToBeRecorded(LogConstants.CTX_BUFFER_MGR, MessageLevel.TRACE)) {
    		LogManager.logTrace(LogConstants.CTX_BUFFER_MGR, "Reserving buffer space", count, mode); //$NON-NLS-1$
    	}
    	lock.lock();
	    try {
	    	if (mode == BufferReserveMode.WAIT) {
	    		//don't wait for more than is available
	    		int waitCount = Math.min(count, this.maxReserveKB);
		    	while (waitCount > 0 && waitCount > this.reserveBatchKB) {
		    		try {
						batchesFreed.await(100, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						throw new TeiidRuntimeException(e);
					}
					waitCount /= 2;
		    	}	
	    	}
	    	if (this.reserveBatchKB >= count || mode == BufferReserveMode.FORCE) {
		    	this.reserveBatchKB -= count;
	    		return count;
	    	}
	    	int result = Math.max(0, this.reserveBatchKB);
	    	this.reserveBatchKB -= result;
	    	return result;
	    } finally {
    		lock.unlock();
    		persistBatchReferences();
    	}
    }
    
	void persistBatchReferences() {
		if (activeBatchKB == 0 || activeBatchKB <= reserveBatchKB) {
    		int memoryCount = activeBatchKB + maxReserveKB - reserveBatchKB;
			if (DataTypeManager.isValueCacheEnabled()) {
    			if (memoryCount < maxReserveKB / 8) {
					DataTypeManager.setValueCacheEnabled(false);
				}
			} else if (memoryCount > maxReserveKB / 4) {
				DataTypeManager.setValueCacheEnabled(true);
			}
			return;
		}
		while (true) {
			ManagedBatchImpl mb = null;
			synchronized (activeBatches) {
				if (activeBatchKB == 0 || activeBatchKB < reserveBatchKB * .8) {
					break;
				}
				Iterator<TupleBufferInfo> iter = activeBatches.values().iterator();
				TupleBufferInfo tbi = iter.next();
				Map.Entry<Integer, ManagedBatchImpl> entry = null;
				if (tbi.lastUsed != null) {
					entry = tbi.batches.floorEntry(tbi.lastUsed - 1);
				}
				if (entry == null) {
					entry = tbi.batches.lastEntry();
				} 
				tbi.removeBatch(entry.getKey());
				if (tbi.batches.isEmpty()) {
					iter.remove();
				}
				mb = entry.getValue();
			}
			try {
				mb.persist();
			} catch (TeiidComponentException e) {
				LogManager.logDetail(LogConstants.CTX_BUFFER_MGR, e, "Error persisting batch, attempts to read that batch later will result in an exception"); //$NON-NLS-1$
			}
		}
	}
	
	@Override
	public int getSchemaSize(List<? extends Expression> elements) {
		int total = 0;
		boolean isValueCacheEnabled = DataTypeManager.isValueCacheEnabled();
		//we make a assumption that the average column size under 64bits is approximately 128bytes
		//this includes alignment, row/array, and reference overhead
		for (Expression element : elements) {
			Class<?> type = element.getType();
			total += SizeUtility.getSize(isValueCacheEnabled, type);
		}
		total += 8*elements.size() + 36;  // column list / row overhead
		total *= processorBatchSize; 
		return Math.max(1, total / 1024);
	}

	public void shutdown() {
	}

	@Override
	public void addTupleBuffer(TupleBuffer tb) {
		cleanDefunctTupleBuffers();
		this.tupleBufferMap.put(tb.getId(), new TupleReference(tb, this.tupleBufferQueue));
	}
	
	@Override
	public void distributeTupleBuffer(String uuid, TupleBuffer tb) {
		tb.setId(uuid);
		addTupleBuffer(tb);
	}
	
	@Override
	public TupleBuffer getTupleBuffer(String id) {		
		cleanDefunctTupleBuffers();
		Reference<TupleBuffer> r = this.tupleBufferMap.get(id);
		if (r != null) {
			return r.get();
		}
		return null;
	}
	
	private void cleanDefunctTupleBuffers() {
		while (true) {
			Reference<?> r = this.tupleBufferQueue.poll();
			if (r == null) {
				break;
			}
			this.tupleBufferMap.remove(((TupleReference)r).id);
		}
	}
	
	static class TupleReference extends WeakReference<TupleBuffer>{
		String id;
		public TupleReference(TupleBuffer referent, ReferenceQueue<? super TupleBuffer> q) {
			super(referent, q);
			id = referent.getId();
		}
	}
	
	public void setUseWeakReferences(boolean useWeakReferences) {
		this.useWeakReferences = useWeakReferences;
	}	
	
	@Override
	public void getState(OutputStream ostream) {
		try {
			ObjectOutputStream out = new ObjectOutputStream(ostream);
			for(String id:this.tupleBufferMap.keySet()) {
				TupleReference tr = this.tupleBufferMap.get(id);
				TupleBuffer tb = tr.get();
				if (tb != null) {
					out.writeObject(tb.getId());
					getTupleBufferState(out, tb);
				}
			}
		} catch (TeiidComponentException e) {
			throw new TeiidRuntimeException(e);
		} catch (IOException e) {
			throw new TeiidRuntimeException(e);
		}				
	}
	
	@Override
	public void getState(String state_id, OutputStream ostream) {
		TupleBuffer buffer = this.getTupleBuffer(state_id);
		if (buffer != null) {
			try {
				ObjectOutputStream out = new ObjectOutputStream(ostream);
				getTupleBufferState(out, buffer);
			} catch (TeiidComponentException e) {
				throw new TeiidRuntimeException(e);
			} catch (IOException e) {
				throw new TeiidRuntimeException(e);
			}
		}
	}

	private void getTupleBufferState(ObjectOutputStream out, TupleBuffer buffer) throws TeiidComponentException, IOException {
		out.writeInt(buffer.getRowCount());
		out.writeInt(buffer.getBatchSize());
		out.writeObject(buffer.getTypes());
		out.writeBoolean(buffer.isPrefersMemory());
		for (int row = 1; row <= buffer.getRowCount(); row+=buffer.getBatchSize()) {
			TupleBatch b = buffer.getBatch(row);
			b.preserveTypes();
			out.writeObject(b);
		}
	}

	@Override
	public void setState(InputStream istream) {
		try {
			ObjectInputStream in = new ObjectInputStream(istream);
			while (true) {
				String state_id = null;
				try {
					state_id = (String)in.readObject();
				} catch (IOException e) {
					break;
				}
				if (state_id != null) {
					setTupleBufferState(state_id, in);
				}
			}
		} catch (IOException e) {
			throw new TeiidRuntimeException(e);
		} catch(ClassNotFoundException e) {
			throw new TeiidRuntimeException(e);
		} catch(TeiidComponentException e) {
			throw new TeiidRuntimeException(e);
		}
		
	}	
	
	@Override
	public void setState(String state_id, InputStream istream) {
		TupleBuffer buffer = this.getTupleBuffer(state_id);
		if (buffer == null) {
			try {
				ObjectInputStream in = new ObjectInputStream(istream);
				setTupleBufferState(state_id, in);
			} catch (IOException e) {
				throw new TeiidRuntimeException(e);
			} catch(ClassNotFoundException e) {
				throw new TeiidRuntimeException(e);
			} catch(TeiidComponentException e) {
				throw new TeiidRuntimeException(e);
			}
		}
	}

	private void setTupleBufferState(String state_id, ObjectInputStream in) throws IOException, ClassNotFoundException, TeiidComponentException {
		int rowCount = in.readInt();
		int batchSize = in.readInt();
		String[] types = (String[])in.readObject();
		boolean prefersMemory = in.readBoolean();
		
		List<ElementSymbol> schema = new ArrayList<ElementSymbol>(types.length);
		for (String type : types) {
			ElementSymbol es = new ElementSymbol("x"); //$NON-NLS-1$
			es.setType(DataTypeManager.getDataTypeClass(type));
			schema.add(es);
		}
		TupleBuffer buffer = createTupleBuffer(schema, "cached", TupleSourceType.FINAL); //$NON-NLS-1$
		buffer.setBatchSize(batchSize);
		buffer.setId(state_id);
		buffer.setPrefersMemory(prefersMemory);
		
		for (int row = 1; row <= rowCount; row+=batchSize) {
			TupleBatch batch = (TupleBatch)in.readObject();
			if (batch == null) {					
				buffer.remove();
				throw new IOException(QueryPlugin.Util.getString("not_found_cache")); //$NON-NLS-1$
			}		
			buffer.addTupleBatch(batch, true);
		}
		buffer.close();
		addTupleBuffer(buffer);
	}

	@Override
	public void setLocalAddress(Serializable address) {
	}

	@Override
	public void droppedMembers(Collection<Serializable> addresses) {
	}	
}
