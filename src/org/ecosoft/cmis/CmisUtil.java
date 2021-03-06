package org.ecosoft.cmis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

/**
 * @author kittiu
 * @author a42niem - Dirk Niemeyer - action42 GmbH
 * 
 * basic work done by kittiu
 * adapted to Alfresco 5 and extented for versioning 
 */

public class CmisUtil 
{
	public static Session createCmisSession(String user, String password, String url) 
	{
	    SessionFactory sessionFactory = SessionFactoryImpl.newInstance();
	    Map<String, String> parameter = new HashMap<String, String>();
	    parameter.put(SessionParameter.USER, user);
	    parameter.put(SessionParameter.PASSWORD, password);
	    parameter.put(SessionParameter.ATOMPUB_URL, url); 
	    parameter.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
	
	    Repository repository = sessionFactory.getRepositories(parameter).get(0);
	    return repository.createSession();
	}
  
	public static Folder getFolder(Session session, String folderName) 
	{
	    ObjectType type = session.getTypeDefinition("cmis:folder");
	    PropertyDefinition<?> objectIdPropDef = type.getPropertyDefinitions().get(PropertyIds.OBJECT_ID);
	    String objectIdQueryName = objectIdPropDef.getQueryName();
	    
	    ItemIterable<QueryResult> results = session.query("SELECT * FROM cmis:folder WHERE cmis:name='" + folderName + "'", false);
	    for (QueryResult qResult : results) 
	    {
			String objectId = qResult.getPropertyValueByQueryName(objectIdQueryName);
			return (Folder) session.getObject(session.createObjectId(objectId));
	    }
	    return null;
	}
  
	public static Folder createFolder(Session session, Folder parentFolder, String folderName) 
	{
	    Map<String, String> folderProps = new HashMap<String, String>();
	    folderProps.put(PropertyIds.NAME, folderName);
	    folderProps.put(PropertyIds.OBJECT_TYPE_ID, "cmis:folder");
	
	    ObjectId folderObjectId = session.createFolder(folderProps, parentFolder, null, null, null);
	    return (Folder) session.getObject(folderObjectId);
	}
  
	public static Document createDocument(Session session, Folder folder, String fileName, String mimetype, byte[] content) throws Exception 
	{
	    Map<String, Object> docProps = new HashMap<String, Object>();
	    docProps.put(PropertyIds.NAME, fileName);
	    docProps.put(PropertyIds.OBJECT_TYPE_ID, "cmis:document");
	    
	    ByteArrayInputStream in = new ByteArrayInputStream(content);
	    ContentStream contentStream = session.getObjectFactory().createContentStream(fileName, content.length, mimetype, in);
	    
	    ObjectId documentId = session.createDocument(docProps, session.createObjectId((String) folder.getPropertyValue(PropertyIds.OBJECT_ID)), contentStream, null, null, null, null);
	    Document document = (Document) session.getObject(documentId);
	    return document;
	}
	
	public static Document createiDempiereAttachment(Session session, Folder folder, String fileName, String mimetype, byte[] content, String tableName, String recordId, String checkSum) throws Exception 
	{
	    Map<String, Object> docProps = new HashMap<String, Object>();
	    
	    ByteArrayInputStream in = new ByteArrayInputStream(content);
	    ContentStream contentStream = session.getObjectFactory().createContentStream(fileName, content.length, mimetype, in);
	    // check if entry already exists
	    Document document = null;
	    try {
	    	// we better not rely on this path
	    	// document = (Document) session.getObjectByPath("/" + folder.getName() + "/" + tableName + "/" + recordId + "/" + fileName);
	    	// so lets query the repository
	    	StringBuilder query = new StringBuilder("SELECT cmis:objectId FROM id:attachment WHERE ")
	    	.append(" id:tablename='").append(tableName)
	    	.append("' AND id:recordid='").append(recordId)
	    	.append("' AND cmis:name='").append(fileName).append("'");
	    	ItemIterable<QueryResult> searchResult = session.query(query.toString(), false);
	    	Iterator<QueryResult> it = searchResult.iterator(); 
	    	while (it.hasNext()) {
	    		QueryResult resultRow = it.next(); 
	    		if (resultRow == null)
	    			break;
	    		String oldObjectId = (String) resultRow.getPropertyByQueryName("cmis:objectId").getFirstValue();
	    		document = (Document) session.getObject(oldObjectId);
	    	}

	    }
	    catch (CmisObjectNotFoundException e) {
	    	;
	    }
	    catch (CmisRuntimeException e) {
	    	;
	    }
	    if (document!=null) {
	    	// make sure we work on latest version
	    	if (!document.isLatestVersion()) {
	    		// get latest major version
	    		document = document.getObjectOfLatestVersion(true); 
	    	}

	    	Property<Object> objCheckSum = document.getProperty("id:checksum");
	    	if (objCheckSum.getValue()!=null && objCheckSum.getValue().equals(checkSum)) {
	    		// we give the existing document back
	    		return document;
	    	}
	    	// else we have a different checksum, so we suppose a new version
	    	ObjectId pwcId = document.checkOut();   	
	    	document.refresh();	    	
	    	Document pwc = (Document) session.getObject(pwcId);
	    	// we check in the new version with same properties
	    	ObjectId newDocId = pwc.checkIn(true, null, contentStream, "Another version");
	    	document = (Document) session.getObject(newDocId);
	    	// set and update the checksum property
	    	docProps.put("id:checksum", checkSum);
	    	document.updateProperties(docProps);
	    }
	    // nothing found, create new
	    else {
		    docProps.put(PropertyIds.NAME, fileName);
		    docProps.put(PropertyIds.OBJECT_TYPE_ID, "D:id:attachment");
		    docProps.put("id:tablename", tableName);
		    docProps.put("id:recordid", recordId);
		    docProps.put("id:checksum", checkSum);
	    	document = (Document) folder.createDocument(docProps, contentStream, null);
	    }
	    return document;
	}
  
	public static byte[] toByteArray(InputStream input) throws IOException 
	{
		ByteArrayOutputStream output = null;
		try 
		{
		      output = new ByteArrayOutputStream();
		      byte[] buffer = new byte[4096];
		      long count = 0;
		      int n = 0;
		      while (-1 != (n = input.read(buffer))) 
		      {
		    	  output.write(buffer, 0, n);
		    	  count += n;
		      }
		      return output.toByteArray();
	    } 
		finally
		{
			if (output != null) 
			{
				output.close();
			}
	    }
	}

}
