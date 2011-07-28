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
package com.aperigeek.dropvault.web.service;

import com.aperigeek.dropvault.web.beans.User;
import com.aperigeek.dropvault.web.dao.user.InvalidPasswordException;
import com.aperigeek.dropvault.web.dao.user.UsersDAO;
import com.aperigeek.dropvault.web.rest.webdav.NotAuthorizedException;
import com.aperigeek.dropvault.web.rest.webdav.ProtocolException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import org.apache.commons.codec.binary.Base64;

/**
 *
 * @author Vivien Barousse
 */
@Stateless
public class AuthenticationService {
    
    @EJB
    private HashService hashService;
    
    @EJB
    private UsersDAO usersDAO;
    
    public User checkAuthentication(String header) 
            throws InvalidPasswordException, NotAuthorizedException, ProtocolException {
        
        if (header == null) {
            throw new InvalidPasswordException();
        }
        
        Pattern headerPattern = Pattern.compile("Basic (.+)");
        Matcher headerMatcher = headerPattern.matcher(header);
        
        if (!headerMatcher.matches()) {
            throw new ProtocolException("Invalid Authorization header");
        }
        
        String b64 = headerMatcher.group(1);
        String headerContent = new String(Base64.decodeBase64(b64));
        
        Pattern passwordPattern = Pattern.compile("(.+):([^:]+)");
        Matcher passwordMatcher = passwordPattern.matcher(headerContent);

        if (!passwordMatcher.matches()) {
            throw new ProtocolException("Invalid authentication header");
        }

        String user = passwordMatcher.group(1);
        String password = passwordMatcher.group(2);
        String hashPassword = hashService.hash(password);
        
        if (usersDAO.login(user, hashPassword)) {
            return new User(user, password);
        }
        
        throw new InvalidPasswordException();
    }
    
}
