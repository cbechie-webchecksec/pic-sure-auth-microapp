package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.PrivilegeRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.jwt.JWTUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/application")
public class ApplicationService extends BaseEntityService<Application> {

	Logger logger = LoggerFactory.getLogger(ApplicationService.class);

	@Inject
	ApplicationRepository applicationRepo;

	@Inject
	PrivilegeRepository privilegeRepo;

	@Context
	SecurityContext securityContext;

	public ApplicationService() {
		super(Application.class);
	}

	@GET
	@Path("/{applicationId}")
	@RolesAllowed({SYSTEM, SUPER_ADMIN})
	public Response getApplicationById(
			@PathParam("applicationId") String applicationId) {
		return getEntityById(applicationId,applicationRepo);
	}

	@GET
	@Path("")
	@RolesAllowed({SYSTEM, SUPER_ADMIN})
	public Response getApplicationAll() {
		return getEntityAll(applicationRepo);
	}

	@Transactional
	@POST
	@RolesAllowed({SYSTEM, SUPER_ADMIN})
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/")
	public Response addApplication(List<Application> applications){
		checkAssociation(applications);
		List<Application> appEntities = addOrUpdate(applications, true, applicationRepo);
		for(Application application : appEntities) {
			Map<String, Object> claims = new HashMap<>(Map.of("user_id","PSAMA_APPLICATION|" + application.getName().toString()));
			try{
				String token = JWTUtil.createJwtToken(
						JAXRSConfiguration.clientSecret, null, null,
						claims,
						"PSAMA_APPLICATION|" + application.getUuid().toString(), 1000 * 60 * 60 * 24 * 365);
				application.setToken(token);
			} catch(Exception e) {
				logger.error("", e);
			}
		}

		return updateEntity(appEntities, applicationRepo);
	}

	@PUT
	@RolesAllowed({SYSTEM, SUPER_ADMIN})
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/")
	public Response updateApplication(List<Application> applications){
		checkAssociation(applications);
		return updateEntity(applications, applicationRepo);
	}

	@Transactional
	@DELETE
	@RolesAllowed({SYSTEM, SUPER_ADMIN})
	@Path("/{applicationId}")
	public Response removeById(@PathParam("applicationId") final String applicationId) {
		Application application = applicationRepo.getById(UUID.fromString(applicationId));
		return removeEntityById(applicationId, applicationRepo);
	}

	private void checkAssociation(List<Application> applications){
		for (Application application: applications){
			if (application.getPrivileges() != null) {
				Set<Privilege> privileges = new HashSet<>();
				application.getPrivileges().stream().forEach(p -> {
					Privilege privilege = privilegeRepo.getById(p.getUuid());
					if (privilege != null){
						privilege.setApplication(application);
						privileges.add(privilege);
					} else {
						logger.error("Didn't find privilege by uuid: " + p.getUuid());
					}
				});
				application.setPrivileges(privileges);

			}
		}

	}

}
