package edu.harvard.hms.dbmi.avillach.auth.security;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import io.jsonwebtoken.*;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

@Provider
public class JWTFilter implements ContainerRequestFilter {

	@Context
	private UriInfo uriInfo;

	Logger logger = LoggerFactory.getLogger(JWTFilter.class);

	@Context
	ResourceInfo resourceInfo;
	
	@Inject
	UserRepository userRepo;

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {

		/**
		 * skip the filter in certain cases
		 */
		if (uriInfo.getPath().endsWith("authentication")) {
			return;
		}

		logger.debug("Entered jwtfilter.filter()...");

		String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
		if (authorizationHeader == null || authorizationHeader.isEmpty()) {
			throw new NotAuthorizedException("No authorization header found.");
		}
		String token = authorizationHeader.substring(6).trim();

		String userForLogging = null;

		try {
			User authenticatedUser = null;


			authenticatedUser = callLocalAuthentication(requestContext, token);

			if (authenticatedUser == null) {
				logger.error("Cannot extract a user from token: " + token);
				throw new NotAuthorizedException("Cannot find or create a user");
			}

			// currently only user id will be logged, in the future, it might contain roles and other information,
			// like xxxuser|roles|otherInfo
			userForLogging = authenticatedUser.getUserId();

			// check authorization of the authenticated user
			checkRoles(authenticatedUser, resourceInfo
					.getResourceMethod().isAnnotationPresent(RolesAllowed.class)
					? resourceInfo.getResourceMethod().getAnnotation(RolesAllowed.class).value()
					: new String[]{});

			logger.info("User - " + userForLogging + " - has just passed all the authentication and authorization layers.");

		} catch (NotAuthorizedException e) {
			// the detail of this exception should be logged right before the exception thrown out
//			logger.error("User - " + userForLogging + " - is not authorized. " + e.getChallenges());
			// we should show different response based on role
			requestContext.abortWith(PICSUREResponse.unauthorizedError("User is not authorized. " + e.getChallenges()));
		} catch (Exception e){
			// we should show different response based on role
			e.printStackTrace();
			requestContext.abortWith(PICSUREResponse.applicationError("Inner application error, please contact system admin"));
		}
	}

	/**
	 * check if user contains the input list of roles
	 *
	 * @param authenticatedUser
	 * @param rolesAllowed
	 * @return
	 */
	private boolean checkRoles(User authenticatedUser, String[] rolesAllowed) throws NotAuthorizedException{

		String logMsg = "The roles of the user - id: " + authenticatedUser.getUserId() + " - "; //doesn't match the required restrictions";
		boolean b = true;
		if (rolesAllowed.length < 1) {
			return true;
		}

		if (authenticatedUser.getRoles() == null) {
			logger.error(logMsg + "user doesn't have a role.");
			throw new NotAuthorizedException("user doesn't have a role.");
		}

		for (String role : rolesAllowed) {
			if(!authenticatedUser.getRoles().contains(role)) {
				logger.error(logMsg + "doesn't match the required role restrictions, role from user: "
						+ authenticatedUser.getRoles() + ", role required: " + Arrays.toString(rolesAllowed));
				throw new NotAuthorizedException("doesn't match the required role restrictions.");
			}
		}
		return b;
	}

	/**
	 *
	 * @param requestContext
	 * @param token
	 * @return
	 * @throws NotAuthorizedException
	 */
	private User callLocalAuthentication(ContainerRequestContext requestContext, String token) throws NotAuthorizedException{
		Jws<Claims> jws;

		try {
			jws = Jwts.parser().setSigningKey(JAXRSConfiguration.clientSecret.getBytes()).parseClaimsJws(token);
		} catch (SignatureException e) {
			try {
				jws = Jwts.parser().setSigningKey(Base64.decodeBase64(JAXRSConfiguration.clientSecret
						.getBytes("UTF-8")))
						.parseClaimsJws(token);
			} catch (UnsupportedEncodingException ex){
				logger.error("callLocalAuthentication() clientSecret encoding UTF-8 is not supported. "
						+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				throw new NotAuthorizedException("encoding is not supported");
			} catch (JwtException | IllegalArgumentException ex) {
				logger.error("callLocalAuthentication() throws: " + e.getClass().getSimpleName() + ", " + e.getMessage());
				throw new NotAuthorizedException(ex.getClass().getSimpleName());
			}
		} catch (JwtException | IllegalArgumentException e) {
			logger.error("callLocalAuthentication() throws: " + e.getClass().getSimpleName() + ", " + e.getMessage());
			throw new NotAuthorizedException(e.getClass().getSimpleName());
		}

		if (jws == null) {
			logger.error("callLocalAuthentication() get null for jws body by parsing Token - " + token + " - already successfully parsed the token" );
			throw new NotAuthorizedException("please contact admin to see the log");
		}


		String subject = jws.getBody().getSubject();
		String userId = jws.getBody().get(JAXRSConfiguration.userIdClaim, String.class);

		return userRepo.findOrCreate(new User().setSubject(subject).setUserId(userId));
	}
}
