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
package com.aperigeek.dropvault.web.rest;

import com.aperigeek.dropvault.web.beans.Resource;
import com.aperigeek.dropvault.web.beans.User;
import com.aperigeek.dropvault.web.dao.MongoFileService;
import com.aperigeek.dropvault.web.dao.user.InvalidPasswordException;
import com.aperigeek.dropvault.web.rest.webdav.NotAuthorizedException;
import com.aperigeek.dropvault.web.rest.webdav.ProtocolException;
import com.aperigeek.dropvault.web.service.AuthenticationService;
import com.aperigeek.dropvault.web.service.IndexException;
import com.aperigeek.dropvault.web.service.IndexService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import org.json.JSONArray;

/**
 *
 * @author Vivien Barousse
 */
@Path("query")
@Stateless
public class SearchService {
    
    public static final String DAV_BASE = "/";
    
    @EJB
    private IndexService indexService;
    
    @EJB
    private MongoFileService fileService;
    
    @EJB
    private AuthenticationService authenticationService;
    
    @GET
    @Produces("application/json")
    public Response query(@HeaderParam("Authorization") String authorization,
            @QueryParam("q") String query) throws IndexException {
        
        User user;
        try {
            user = authenticationService.checkAuthentication(authorization);
        } catch (InvalidPasswordException ex) {
            return Response.status(401)
                    .header("WWW-Authenticate", "Basic realm=\"Search authentication\"")
                    .build();
        } catch (NotAuthorizedException ex) {
            return Response.status(403).build();
        } catch (ProtocolException ex) {
            return Response.status(400).build();
        }
        
        URI userUri = URI.create(DAV_BASE);
        
        List<String> uris = new ArrayList<String>();
        List<String> ids = indexService.search(user.getUsername(), user.getPassword(), query);
        for (String id : ids) {
            Resource res = fileService.getResource(id);
            Stack<Resource> path = new Stack<Resource>();
            Resource parent = res;
            while (parent != null) {
                path.push(parent);
                parent = fileService.getParent(parent);
            }
            
            // Remove the user's root folder, we don't want it in the path
            path.pop();
            
            UriBuilder builder = UriBuilder.fromUri(userUri);
            while (!path.empty()) {
                Resource e = path.pop();
                builder.path(e.getName());
            }
            uris.add(builder.build().toString());
        }
        
        JSONArray array = new JSONArray(uris);
        return Response.ok(array.toString()).build();
    }
    
}
