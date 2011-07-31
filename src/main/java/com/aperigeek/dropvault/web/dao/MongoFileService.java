/*  
 * This file is part of dropvault.
 *
 * dropvault is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dropvault is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with dropvault.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aperigeek.dropvault.web.dao;

import com.aperigeek.dropvault.web.beans.Resource;
import com.aperigeek.dropvault.web.service.ContentExtractionService;
import com.aperigeek.dropvault.web.service.FileTypeDetectionService;
import com.aperigeek.dropvault.web.service.IndexException;
import com.aperigeek.dropvault.web.service.IndexService;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.KeyStore.SecretKeyEntry;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import org.bson.types.ObjectId;

/**
 *
 * @author Vivien Barousse
 */
@Stateless
public class MongoFileService {
    
    // TODO: export to configuration file
    private static final File storageFolder = new File("/home/dropvault/storage");
    
    private static final File secretsFolder = new File("/home/dropvault/secret");
    
    static {
        if (!storageFolder.exists()) {
            storageFolder.mkdirs();
        }
        if (!secretsFolder.exists()) {
            secretsFolder.mkdirs();
        }
    }
    
    @EJB
    private MongoService mongo;
    
    @EJB
    private FileTypeDetectionService fileTypeDetectionService;
    
    @EJB
    private ContentExtractionService extractionService;
    
    @EJB
    private IndexService indexService;
    
    public Resource getRootFolder(String username) {
        DBCollection files = mongo.getDataBase().getCollection("files");
        
        DBObject filter = new BasicDBObject();
        filter.put("user", username);
        filter.put("root", true);
        
        DBObject root = files.findOne(filter);
        
        if (root == null) {
            BasicDBObject newRoot = new BasicDBObject();
            newRoot.append("type", Resource.ResourceType.FOLDER.toString());
            newRoot.append("name", username);
            newRoot.append("root", true);
            newRoot.append("user", username);
            newRoot.append("creationDate", new Date());
            newRoot.append("modificationDate", new Date());
            files.insert(newRoot);
            root = newRoot;
        }
        
        Resource res = buildResource(root);
        
        return res;
    }
    
    public List<Resource> getChildren(Resource resource) {
        DBCollection col = mongo.getDataBase().getCollection("files");
        
        DBObject filter = new BasicDBObject();
        filter.put("parent", resource.getId());
        
        List<Resource> children = new ArrayList<Resource>();
        
        DBCursor cursor = col.find(filter);
        while (cursor.hasNext()) {
            children.add(buildResource(cursor.next()));
        }
        
        return children;
    }
    
    // TODO: implement support for ..
    public Resource getChild(Resource resource, String name) {
        if ("".equals(name) || ".".equals(name)) {
            return resource;
        }
        
        DBCollection col = mongo.getDataBase().getCollection("files");
        
        DBObject filter = new BasicDBObject();
        filter.put("name", name);
        filter.put("parent", resource.getId());
        
        DBObject child = col.findOne(filter);
        
        Resource childRes = buildResource(child);
        
        return childRes;
    }
    
    public Resource mkcol(String username, String resource) throws ResourceAlreadyExistsException, ResourceNotFoundException {
        String[] path = resource.split("/");
        Resource parent = getRootFolder(username);
        for (int i = 0; i < path.length - 1; i++) {
            parent = getChild(parent, path[i]);
            if (parent == null) {
                throw new ResourceNotFoundException();
            }
        }
        
        if (getChild(parent, path[path.length - 1]) != null) {
            throw new ResourceAlreadyExistsException();
        }
        
        DBCollection col = mongo.getDataBase().getCollection("files");
        
        DBObject obj = new BasicDBObject();
        obj.put("type", Resource.ResourceType.FOLDER.toString());
        obj.put("user", "viv");
        obj.put("name", path[path.length - 1]);
        obj.put("creationDate", new Date());
        obj.put("modificationDate", new Date());
        obj.put("parent", parent.getId());
        
        col.insert(obj);
        
        col.update(new BasicDBObject("_id", parent.getId()), 
                new BasicDBObject("$set", 
                new BasicDBObject("modificationDate", new Date())));
        
        return buildResource(obj);
    }
    
    public Resource getResource(String id) {
        ObjectId oid = new ObjectId(id);
        DBObject query = new BasicDBObject("_id", oid);
        DBCollection files = mongo.getDataBase().getCollection("files");
        DBObject result = files.findOne(query);
        return buildResource(result);
    }
    
    public Resource getResource(String username, String resource) throws ResourceNotFoundException {
        String[] path = resource.split("/");
        Resource parent = getRootFolder(username);
        for (int i = 0; i < path.length; i++) {
            if (parent == null) {
                throw new ResourceNotFoundException();
            }
            parent = getChild(parent, path[i]);
        }
        return parent;
    }
    
    public Resource getParent(Resource res) {
        DBCollection files = mongo.getDataBase().getCollection("files");
        DBObject resQuery = new BasicDBObject("_id", res.getId());
        DBObject dbRes = files.findOne(resQuery);
        DBObject dbParent = files.findOne(new BasicDBObject("_id", dbRes.get("parent")));
        return buildResource(dbParent);
    }
    
    public void put(String username, String resource, byte[] data, 
            String contentType, char[] password) throws ResourceNotFoundException, IOException {
        String[] path = resource.split("/");
        Resource parent = getRootFolder(username);
        for (int i = 0; i < path.length - 1; i++) {
            parent = getChild(parent, path[i]);
            if (parent == null) {
                throw new ResourceNotFoundException();
            }
        }
        
        if (contentType == null) {
            contentType = fileTypeDetectionService
                    .detectFileType(path[path.length - 1], data);
        }
        
        DBCollection files = mongo.getDataBase().getCollection("files");
        DBCollection contents = mongo.getDataBase().getCollection("contents");
        
        File dataFile = createDataFile(data, username, password);
        
        Resource child = getChild(parent, path[path.length - 1]);
        if (child != null) {
            DBObject filter = new BasicDBObject();
            filter.put("_id", child.getId());
            DBObject update = new BasicDBObject("modificationDate", new Date());
            update.put("contentLength", data.length);
            update.put("contentType", contentType);
            files.update(filter, new BasicDBObject("$set", update));
            
            contents.update(new BasicDBObject("resource", child.getId()), 
                    new BasicDBObject("$set", new BasicDBObject("file", dataFile.getAbsolutePath())));
        } else {
            DBObject childObj = new BasicDBObject();
            ObjectId objId = new ObjectId();
            childObj.put("_id", objId);
            childObj.put("user", username);
            childObj.put("name", path[path.length - 1]);
            childObj.put("parent", parent.getId());
            childObj.put("type", Resource.ResourceType.FILE.toString());
            childObj.put("creationDate", new Date());
            childObj.put("modificationDate", new Date());
            childObj.put("contentType", contentType);
            childObj.put("contentLength", data.length);
            
            files.insert(childObj);
            
            DBObject content = new BasicDBObject();
            content.put("resource", objId);
            content.put("file", dataFile.getAbsolutePath());
            
            contents.insert(content);
        
            files.update(new BasicDBObject("_id", parent.getId()), 
                    new BasicDBObject("$set", 
                    new BasicDBObject("modificationDate", new Date())));
            
            child = buildResource(childObj);
        }
        
        try {
            Map<String, String> metadata = extractionService.extractContent(path[path.length - 1], 
                    new ByteArrayInputStream(data), 
                    contentType);
            
            metadata.put("name", path[path.length - 1]);
            
            indexService.remove(username, new String(password), child.getId().toString());
            indexService.index(username, new String(password), child.getId().toString(), metadata);
        } catch (Exception ex) {
            Logger.getLogger(MongoFileService.class.getName()).log(Level.SEVERE, "Index failed for " + path[path.length - 1], ex);
        }
    }
    
    public void move(String username, Resource source, String dest) throws ResourceNotFoundException {
        String[] path = dest.split("/");
        Resource parent = getRootFolder(username);
        for (int i = 0; i < path.length - 1; i++) {
            parent = getChild(parent, path[i]);
            if (parent == null) {
                throw new ResourceNotFoundException();
            }
        }
        
        DBCollection files = mongo.getDataBase().getCollection("files");
        
        DBObject update = new BasicDBObject("$set", new BasicDBObjectBuilder()
                .append("parent", parent.getId())
                .append("name", path[path.length - 1])
                .get());
        
        DBObject filter = new BasicDBObject("_id", source.getId());
        
        DBObject current = files.findOne(filter);
        files.update(new BasicDBObject("_id", (ObjectId) current.get("parent")), 
                new BasicDBObject("$set", 
                new BasicDBObject("modificationDate", new Date())));
        
        files.update(filter, update);
        
        files.update(new BasicDBObject("_id", parent.getId()), 
                new BasicDBObject("$set", 
                new BasicDBObject("modificationDate", new Date())));
    }
    
    public byte[] get(String username, Resource resource, char[] password) throws IOException {
        DBCollection col = mongo.getDataBase().getCollection("contents");
        
        DBObject filter = new BasicDBObject();
        filter.put("resource", resource.getId());
        
        DBObject result = col.findOne(filter);
        byte[] binary;
        if (result.containsField("file")) {
            String fileName = (String) result.get("file");
            File dataFile = new File(fileName);
            binary = readFile(dataFile, username, password);
        } else {
            binary = (byte[]) result.get("binary");
        }
        
        return binary;
    }
    
    public void delete(String username, String password, Resource resource) {
        DBCollection files = mongo.getDataBase().getCollection("files");
        DBCollection contents = mongo.getDataBase().getCollection("contents");
        
        for (Resource child : getChildren(resource)) {
            delete(username, password, child);
        }
        
        DBObject filter = new BasicDBObject("_id", resource.getId());
        
        DBObject current = files.findOne(filter);
        files.update(new BasicDBObject("_id", (ObjectId) current.get("parent")), 
                new BasicDBObject("$set", 
                new BasicDBObject("modificationDate", new Date())));
        
        files.remove(filter);
        contents.remove(new BasicDBObject("resource", resource.getId()));
        
        try {
            indexService.remove(username, password, resource.getId().toString());
        } catch (IndexException ex) {
            Logger.getLogger(MongoFileService.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    protected Resource buildResource(DBObject obj) {
        if (obj == null) {
            return null;
        }
        
        Resource childRes = new Resource(
                (ObjectId) obj.get("_id"),
                (String) obj.get("name"),
                (Date) obj.get("creationDate"),
                (Date) obj.get("modificationDate"));
        
        if ("FILE".equals(obj.get("type"))) {
            childRes.setType(Resource.ResourceType.FILE);
            childRes.setContentLength((Integer) obj.get("contentLength"));
            childRes.setContentType((String) obj.get("contentType"));
        }
        
        return childRes;
    }
    
    protected byte[] readFile(File file, String username, char[] password) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream fIn = new BufferedInputStream(new FileInputStream(file));
            
            Cipher cipher = Cipher.getInstance("Blowfish");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(username, password));
            
            CipherInputStream in = new CipherInputStream(fIn, cipher);
            
            byte[] buffer = new byte[4096];
            int readed;
            while ((readed = in.read(buffer)) != -1) {
                out.write(buffer, 0, readed);
            }
            
            in.close();
            
            return out.toByteArray();
        } catch (Exception ex) {
            // TODO: better exception handling
            Logger.getAnonymousLogger().log(Level.SEVERE, "ERROR", ex);
            throw new RuntimeException(ex);
        }
    }
    
    protected File createDataFile(byte[] data, String username, char[] password) throws IOException {
        try {
            File file = new File(storageFolder, UUID.randomUUID().toString());
            
            Cipher cipher = Cipher.getInstance("Blowfish");
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(username, password));
            
            OutputStream fOut = new BufferedOutputStream(new FileOutputStream(file));
            CipherOutputStream out = new CipherOutputStream(fOut, cipher);
            
            out.write(data);
            
            out.flush();
            out.close();
            fOut.flush();
            fOut.close();
            
            return file;
        } catch (Exception ex) {
            // TODO: better exception handling
            Logger.getAnonymousLogger().log(Level.SEVERE, "ERROR", ex);
            throw new RuntimeException(ex);
        }
    }
    
    protected SecretKey getSecretKey(String username, char[] password) {
        try {
            KeyStore store = getKeyStore(username, password);
            SecretKeyEntry entry = (SecretKeyEntry) store.getEntry(username, new KeyStore.PasswordProtection(password));
            return entry.getSecretKey();
        } catch (Exception ex) {
            // TODO: better exception handling
            throw new RuntimeException(ex);
        }
    }
    
    protected KeyStore getKeyStore(String username, char[] password) {
        try {
            File keyStoreFile = new File(secretsFolder, username + ".jks");
            KeyStore keyStore = KeyStore.getInstance("JCEKS");
            if (keyStoreFile.exists()) {
                keyStore.load(new FileInputStream(keyStoreFile), password);
                return keyStore;
            } else {
                KeyGenerator gen = KeyGenerator.getInstance("Blowfish");
                SecretKey key = gen.generateKey();
                
                keyStore.load(null, password);
                keyStore.setEntry(username, new SecretKeyEntry(key), new KeyStore.PasswordProtection(password));
                
                keyStore.store(new FileOutputStream(keyStoreFile), password);
                
                return keyStore;
            }
        } catch (Exception ex) {
            // TODO: better exception handling
            Logger.getAnonymousLogger().log(Level.SEVERE, "ERROR", ex);
            throw new RuntimeException(ex);
        }
    }
    
}
