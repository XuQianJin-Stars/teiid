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

package org.teiid.arquillian;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;

import javax.ws.rs.core.Response;

import org.apache.cxf.jaxrs.client.WebClient;
import org.jboss.arquillian.junit.Arquillian;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.teiid.adminapi.Admin;
import org.teiid.adminapi.AdminException;
import org.teiid.adminapi.AdminFactory;
import org.teiid.core.util.Base64;
import org.teiid.core.util.ObjectConverterUtil;
import org.teiid.core.util.ReaderInputStream;
import org.teiid.core.util.UnitTestUtil;
import org.teiid.jdbc.AbstractMMQueryTestCase;

@RunWith(Arquillian.class)
@SuppressWarnings("nls")
public class IntegrationTestOData extends AbstractMMQueryTestCase {

	private Admin admin;

	@Before
	public void setup() throws Exception {
		admin = AdminFactory.getInstance().createAdmin("localhost", 9999, "admin", "admin".toCharArray());
	}

	@After
	public void teardown() throws AdminException {
		AdminUtil.cleanUp(admin);
		admin.close();
	}

	@Test
	public void testOdata() throws Exception {
		String vdb = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + 
				"<vdb name=\"Loopy\" version=\"1\">\n" + 
				"    <model name=\"MarketData\">\n" + 
				"        <source name=\"text-connector2\" translator-name=\"loopback\" />\n" + 
				"         <metadata type=\"DDL\"><![CDATA[\n" + 
				"                CREATE FOREIGN TABLE G1 (e1 string, e2 integer PRIMARY KEY);\n" + 
				"                CREATE FOREIGN TABLE G2 (e1 string, e2 integer PRIMARY KEY) OPTIONS (UPDATABLE 'true');\n" + 
				"        ]]> </metadata>\n" + 
				"    </model>\n" + 
				"</vdb>";
		
		admin.deploy("loopy-vdb.xml", new ReaderInputStream(new StringReader(vdb), Charset.forName("UTF-8")));
		
		assertTrue(AdminUtil.waitForVDBLoad(admin, "Loopy", 1, 3));
		
		WebClient client = WebClient.create("http://localhost:8080/odata/loopy.1/$metadata");
		client.header("Authorization", "Basic " + Base64.encodeBytes(("user:user").getBytes())); //$NON-NLS-1$ //$NON-NLS-2$
		Response response = client.invoke("GET", null);
		
		int statusCode = response.getStatus();
		assertEquals(200, statusCode);
		assertEquals(ObjectConverterUtil.convertFileToString(UnitTestUtil.getTestDataFile("loopy-metadata-results.txt")), ObjectConverterUtil.convertToString((InputStream)response.getEntity()));
		
		//make sure that datetime works
		client = WebClient.create("http://localhost:8080/odata/loopy.1/G1?$filter=e1%20eq%20datetime'2000-01-01T01:01:01'");
		client.header("Authorization", "Basic " + Base64.encodeBytes(("user:user").getBytes())); //$NON-NLS-1$ //$NON-NLS-2$
		response = client.invoke("GET", null);
		assertEquals(200, statusCode);
	}
}