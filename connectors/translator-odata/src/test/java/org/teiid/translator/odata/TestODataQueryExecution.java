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
package org.teiid.translator.odata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.activation.DataSource;
import javax.xml.ws.Dispatch;
import javax.xml.ws.Service.Mode;
import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.http.HTTPBinding;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.teiid.cdk.api.TranslationUtility;
import org.teiid.core.util.ObjectConverterUtil;
import org.teiid.core.util.UnitTestUtil;
import org.teiid.language.Command;
import org.teiid.language.QueryExpression;
import org.teiid.query.metadata.TransformationMetadata;
import org.teiid.translator.ExecutionContext;
import org.teiid.translator.ResultSetExecution;
import org.teiid.translator.WSConnection;

@SuppressWarnings({"nls", "unused"})
public class TestODataQueryExecution {
	private ODataExecutionFactory translator;
	private TranslationUtility utility;
	
    @Before
    public void setUp() throws Exception {
    	this.translator = new ODataExecutionFactory();
    	this.translator.start();

    	TransformationMetadata metadata = TestDataEntitySchemaBuilder.getNorthwindMetadataFromODataXML();
    	this.utility = new TranslationUtility(metadata);
    }

	private ResultSetExecution helpExecute(String query, final String resultXML, String expectedURL) throws Exception {
		Command cmd = this.utility.parseCommand(query);
		ExecutionContext context = Mockito.mock(ExecutionContext.class);
		WSConnection connection = Mockito.mock(WSConnection.class);
		
		Map<String, Object> headers = new HashMap<String, Object>();
		headers.put(MessageContext.HTTP_REQUEST_HEADERS, new HashMap<String, List<String>>());
		headers.put(WSConnection.STATUS_CODE, new Integer(200));
		
		Dispatch<DataSource> dispatch = Mockito.mock(Dispatch.class);
		Mockito.stub(dispatch.getRequestContext()).toReturn(headers);
		Mockito.stub(dispatch.getResponseContext()).toReturn(headers);
		
		Mockito.stub(connection.createDispatch(Mockito.eq(HTTPBinding.HTTP_BINDING), Mockito.anyString(), Mockito.eq(DataSource.class), Mockito.eq(Mode.MESSAGE))).toReturn(dispatch);
		
		DataSource ds = new DataSource() {
			@Override
			public OutputStream getOutputStream() throws IOException {
				return new ByteArrayOutputStream();
			}
			@Override
			public String getName() {
				return "result";
			}
			@Override
			public InputStream getInputStream() throws IOException {
				ByteArrayInputStream in = new ByteArrayInputStream(resultXML.getBytes());
				return in;
			}
			@Override
			public String getContentType() {
				return "application/xml";
			}
		};
		Mockito.stub(dispatch.invoke(Mockito.any(DataSource.class))).toReturn(ds);
		
		ResultSetExecution execution = this.translator.createResultSetExecution((QueryExpression)cmd, context, this.utility.createRuntimeMetadata(), connection);
		execution.execute();
		
		ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> binding = ArgumentCaptor.forClass(String.class);
		
		Mockito.verify(connection).createDispatch(binding.capture(), endpoint.capture(), Mockito.eq(DataSource.class), Mockito.eq(Mode.MESSAGE));
		assertEquals(expectedURL, URLDecoder.decode(endpoint.getValue(), "utf-8"));
		return execution;
	}

	@Test
	public void testSimpleSelectNoAssosiations() throws Exception {
		String query = "SELECT CategoryID, CategoryName, Description FROM Categories";
		String expectedURL = "Categories?$select=Description,CategoryName,CategoryID";
		
		FileReader reader = new FileReader(UnitTestUtil.getTestDataFile("categories.xml"));
		ResultSetExecution excution = helpExecute(query, ObjectConverterUtil.convertToString(reader), expectedURL);
		
		assertArrayEquals(new Object[] {1, "Beverages", "Soft drinks, coffees, teas, beers, and ales"}, excution.next().toArray(new Object[3]));
		assertArrayEquals(new Object[] {2, "Condiments", "Sweet and savory sauces, relishes, spreads, and seasonings"}, excution.next().toArray(new Object[3]));
		assertArrayEquals(new Object[] {3, "Confections", "Desserts, candies, and sweet breads"}, excution.next().toArray(new Object[3]));
	}
	
	@Test
	public void testSimpleSelectStar() throws Exception {
		String query = "SELECT * FROM Categories";
		String expectedURL = "Categories?$select=Picture,Description,CategoryName,CategoryID";
		
		FileReader reader = new FileReader(UnitTestUtil.getTestDataFile("categories.xml"));
		ResultSetExecution excution = helpExecute(query, ObjectConverterUtil.convertToString(reader), expectedURL);
	}	

	@Test
	public void testSimpleSelectEmbedded() throws Exception {
		String query = "SELECT * FROM Customers";
		String expectedURL = "Customers?$select=Picture,Description,CategoryName,CategoryID";
		
		FileReader reader = new FileReader(UnitTestUtil.getTestDataFile("categories.xml"));
		ResultSetExecution excution = helpExecute(query, ObjectConverterUtil.convertToString(reader), expectedURL);
	}	
	
	@Test
	public void testSimplePKWhere() throws Exception {
		String query = "SELECT * FROM Categories Where CategoryId = 3";
		String expectedURL = "Categories(3)?$select=Picture,Description,CategoryName,CategoryID";
		
		FileReader reader = new FileReader(UnitTestUtil.getTestDataFile("categories.xml"));
		ResultSetExecution excution = helpExecute(query, ObjectConverterUtil.convertToString(reader), expectedURL);
	}	

	@Test
	public void testSimpleWhere() throws Exception {
		String query = "SELECT * FROM Categories Where CategoryName = 'Beverages'";
		String expectedURL = "Categories?$filter=CategoryName eq 'Beverages'&$select=Picture,Description,CategoryName,CategoryID";
		
		FileReader reader = new FileReader(UnitTestUtil.getTestDataFile("categories.xml"));
		ResultSetExecution excution = helpExecute(query, ObjectConverterUtil.convertToString(reader), expectedURL);
	}	
}
