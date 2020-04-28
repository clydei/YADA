/**
 * 
 */
package com.novartis.opensource.yada.plugin;

import java.io.UnsupportedEncodingException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.novartis.opensource.yada.ConnectionFactory;
import com.novartis.opensource.yada.YADAConnectionException;
import com.novartis.opensource.yada.YADAExecutionException;
import com.novartis.opensource.yada.YADARequest;
import com.novartis.opensource.yada.YADARequestException;
import com.novartis.opensource.yada.YADASQLException;
import com.novartis.opensource.yada.YADASecurityException;
import com.novartis.opensource.yada.util.YADAUtils;

/**
 * @author jfinn
 *
 */
public class Authorizer extends AbstractPostprocessor implements Authorization {

	/**
	 * Contains resource and allowList
	 */
	private JSONObject yadaAuthorization = new JSONObject();

	/**
	 * Contains the name of the resource we are authorizing
	 * 
	 */
	private String resource = new String();

	/**
	 * Contains the credentials of the user we are authorizing
	 * 
	 */
	private String credentials = new String();

	/**
	 * Contains the user identity data from authority
	 */
	private Object identity = new Object();

	/**
	 * Contains the app to authorize
	 */
	private String app = new String();

	/**
	 * Contains the locks to authorize
	 * 
	 */
	private JSONObject locks = new JSONObject();

	/**
	 * Contains the user grant from authority
	 */
	private Object grant = new Object();

	/**
	 * Contains the query result from authority
	 */
	private String result = new String();

	/**
	 * Contains the list of allow qualifiers from A11N
	 */
	private ArrayList<String> allowList = new ArrayList<String>();

	/**
	 * Contains the list of deny qualifiers from A11N
	 */
	private ArrayList<String> denyList = new ArrayList<String>();

	/**
	 * Base implementation, calls {@link #setYADARequest(YADARequest)},
	 * {@link #setRequest(HttpServletRequest)}, {@link #setHTTPHeaders(String)},
	 * {@link #authorizeRequest(YADARequest, String)} and returns
	 * {@link #getResult()}
	 * 
	 * @throws YADAPluginException
	 *           when there is a processing error
	 * @see com.novartis.opensource.yada.plugin.Postprocess#engage(com.novartis.opensource.yada.YADARequest,
	 *      java.lang.String)
	 */
	@Override
	public String engage(YADARequest yadaReq, String result) throws YADAPluginException {

		setResult(result);
		this.setYADARequest(yadaReq);
		this.setRequest(yadaReq.getRequest());

		// Make header available
		try {
			this.setHTTPHeaders(YADA_HDR_AUTH_NAMES);
		} catch (YADARequestException e) {
			String msg = "User is not authorized";
			throw new YADASecurityException(msg);
		}

		authorizeRequest(yadaReq, result);

		return getResult();
	}

	/**
	 * Authorize payload
	 */
	@Override
	public void authorize(String payload) throws YADASecurityException {

		// How we grant authorization
		boolean authorized = false;

		// Check authority for identity
		try {
			setIdentity(obtainIdentity());
		} catch (YADASecurityException | YADARequestException | YADAExecutionException e) {
			String msg = "User is not authorized";
			throw new YADASecurityException(msg);
		}

		try {
			setLocks(obtainLocks());
		} catch (YADARequestException | YADAExecutionException e2) {
			String msg = "A11N Request Exception";
			throw new YADASecurityException(msg);
		}

		if (getLocks().length() > 0) {
			JSONArray key = getLocks().names();
			for (int i = 0; i < key.length(); ++i) {
				String grant = key.getString(i);
				String listtype = getLocks().getString(grant);
				// Unless obtainLocks() is overridden there is only a single whitelist
				// value in the allowList
				if (listtype.equals(AUTH_TYPE_WHITELIST)) {
					// allowList contains locks granting Authorization for
					// this query
					addAllowListEntry(grant);
				}
			}
		}

		// The resource is inserted to the payload
		if (payload != null && !"".equals(payload)) {
			JSONObject j = new JSONObject(payload);
			JSONObject r = j.getJSONObject(RESULT_KEY_RESULTSET);

			if (r.getInt(RESULT_KEY_RECORDS) > 0) {
				JSONArray a = r.getJSONArray(RESULT_KEY_ROWS);
				setResource(a.getJSONObject(0).getString(RESULT_KEY_RESOURCE));

				if (null != getIdentity() && !"".equals(getIdentity())) {
					try {
						// Obtain a relevant GRANT if it exists within IDENTITY
						setGrant(obtainGrant(getApp()));
					} catch (YADASecurityException | YADARequestException | YADAExecutionException e) {
						String msg = "User is not authorized";
						throw new YADASecurityException(msg);
					}
					// Is there a GRANT with APP matching the APP argument?
					if (((JSONArray) getGrant()).length() > 0) {
						if (getAllowList().size() > 0) {
							// pl=Authorizer,<APP>,<LOCK>
							for (int i = 0; i < ((JSONArray) getGrant()).length(); i++) {
								if (getAllowList().contains(((JSONArray) getGrant()).get(i).toString())) {
									authorized = true;
								}
							}
						} else {
							// pl=Authorizer,<APP>
							authorized = true;
						}
					}
				}
			}
		}

		if (authorized == true) {
			JSONObject j = new JSONObject(payload);
			JSONObject r = j.getJSONObject(RESULT_KEY_RESULTSET);
			if (r.getInt(RESULT_KEY_RECORDS) > 0) {
				// JSONArray a = r.getJSONArray(RESULT_KEY_ROWS);
				// JSONObject b = a.getJSONObject(0);
				// b.put(YADA_CK_TKN, this.getToken());
				// a.put(0, b);
				// r.put(RESULT_KEY_RECORDS, a);
				// j.put(RESULT_KEY_RESULTSET, r);
				// // Write the result
				// setResult(j.toString());
				// Return only the token
				setResult((String) this.getToken());
			} else {
				String msg = "User is not authorized";
				throw new YADASecurityException(msg);
			}
		} else

		{
			String msg = "User is not authorized";
			throw new YADASecurityException(msg);
		}
	}

	/**
	 * 
	 * @return yadaauth {Role: [whitelist/blacklist]}
	 * @throws YADARequestException
	 * @throws YADASecurityException
	 * @throws YADAExecutionException
	 * @since 8.7.6
	 */
	public JSONObject obtainLocks() throws YADARequestException, YADASecurityException, YADAExecutionException {

		JSONObject result = new JSONObject();
		// Grab the arguments
		// The first is the APP (required) and set
		// The second is the LOCK (optional) and returned if present
		// This method sets a single whitelist value
		List<String> arg = this.getYADARequest().getArgs();
		if (arg != null && !arg.isEmpty() && arg.size() < 3) {
			if (arg.size() > 1) {
				result.put(arg.get(arg.size() - 1), AUTH_TYPE_WHITELIST);
			}
			setApp(arg.get(0));
		}
		return result;
	}

	/**
	 * Check header for credentials, obtain identity, obtain token, and cache
	 * identity with token
	 * 
	 * @throws YADASecurityException
	 */
	@Override
	public void obtainToken(YADARequest yadaReq) throws YADASecurityException {
		// Check header for credentials
		Pattern rxAuthUsr = Pattern.compile(RX_HDR_AUTH_USR_PREFIX);
		boolean obtainedtoken = false;
		if (this.hasHttpHeaders()) {
			for (int i = 0; i < this.getHttpHeaders().names().length(); i++) {
				// Check for basic auth
				Matcher m1 = rxAuthUsr
				    .matcher((CharSequence) this.getHttpHeaders().get(this.getHttpHeaders().names().getString(i)));
				if (m1.matches() && m1.groupCount() == 3) {// valid header
					setCredentials(m1.group(3));
				}
			}
		}
		if (null != getCredentials() && !"".equals(getCredentials())) {
			// We have credentials
			byte[] credentialBytes = Base64.getDecoder().decode(getCredentials());
			String credentialString = new String(credentialBytes);
			String userid = new String();
			String hasheduserid = new String();
			String pw = new String();
			Pattern rxAuthUsrCreds = Pattern.compile(RX_HDR_AUTH_USR_CREDS);
			Matcher m2 = rxAuthUsrCreds.matcher(credentialString);
			if (m2.matches()) {// found user
				userid = m2.group(1);
				hasheduserid = String.valueOf(userid.hashCode());
				pw = m2.group(2);
			}

			try {
				// use credentials to retrieve user identity
				String id;
				id = obtainIdentity(userid, pw, hasheduserid).toString();
				if (id.length() != 0) {
					// create a token
					generateToken(hasheduserid);
					if (null != this.getToken() && !"".equals(this.getToken())) {
						// add identity to cache using token as key
						this.setCacheEntry(YADA_IDENTITY_CACHE, (String) this.getToken(), id, YADA_IDENTITY_TTL);
						obtainedtoken = true;
					}
				}
			} catch (YADASecurityException | YADARequestException | YADAExecutionException e) {
				String msg = "User is not authorized";
				throw new YADASecurityException(msg);
			}
		}
		if (obtainedtoken == false) {
			String msg = "User is not authorized";
			throw new YADASecurityException(msg);
		}
	}

	/**
	 * @throws YADASecurityException
	 * 
	 * 
	 */
	public void generateToken(String hasheduserid) throws YADASecurityException {
		// issueDate: JWT iat
		// expirationDate: issueDate + identity cache TTL seconds

		String token;
		Instant issueDate = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant expirationDate = issueDate.plus(YADA_IDENTITY_TTL, ChronoUnit.SECONDS);
		try {
			token = JWT.create().withSubject(hasheduserid).withExpiresAt(Date.from(expirationDate))
			    .withIssuer(System.getProperty(JWTISS)).withIssuedAt(Date.from(issueDate))
			    .sign(Algorithm.HMAC512(System.getProperty(JWSKEY)));
		} catch (IllegalArgumentException | JWTCreationException | UnsupportedEncodingException e) {
			String msg = "User is not authorized";
			throw new YADASecurityException(msg);
		}

		this.setToken(token);

	}

	/**
	 * Obtain Identity with basic authentication
	 * 
	 * @return identity
	 * @throws YADARequestException
	 * @throws YADASecurityException
	 * @throws YADAExecutionException
	 * @since 8.7.6
	 * 
	 */

	public Object obtainIdentity(String userid, String pw, String hasheduserid)
	    throws YADARequestException, YADASecurityException, YADAExecutionException {
		JSONObject result = new JSONObject();
		JSONArray a = new JSONArray();
		ArrayList<String> al = new ArrayList<String>();
		// TODO: Replace prepared statement with YADA query
		try (ResultSet rs = YADAUtils.executePreparedStatement(YADA_LOGIN_QUERY, new Object[] { userid, pw });) {
			while (rs.next()) {
				String app = rs.getString(1); // YADA_LOGIN.APP
				al.add(app);
				String key = rs.getString(3); // YADA_LOGIN.ROLE (a key name)
				// a is used to get the form:
				// [{"app":app1,"key":key1},{"app":app1,"key":key2},...{"app":appN,"key":keyN}]
				a.put(new JSONObject().put(YADA_IDENTITY_APP, app).put(YADA_IDENTITY_KEY, key));
			}
			ConnectionFactory.releaseResources(rs);

			// use unique al entries and get the form:
			// [{"app":app1,"keys":[{"key":key1},{"key":key2},...{"key":keyN}]}]
			if (a.length() > 0) {
				// Distill al to applist by removing any duplicate apps
				ArrayList<String> applist = new ArrayList<String>();
				for (String app : al) {
					if (!applist.contains(app)) {
						applist.add(app);
					}
				}
				// assemble identity object
				JSONArray aid = new JSONArray();
				for (int i = 0; i < applist.size(); i++) {
					JSONArray keys = new JSONArray();
					for (int ii = 0; ii < a.length(); ii++) {
						if (applist.get(i).equals(a.getJSONObject(ii).getString(YADA_IDENTITY_APP).toString())) {
							// {YADA_IDENTITY_KEY,
							// a.getJSONObject(i).getString(YADA_IDENTITY_KEY)}
							keys.put(new JSONObject().put(YADA_IDENTITY_KEY, a.getJSONObject(i).getString(YADA_IDENTITY_KEY)));
						}
					}
					aid.put(new JSONObject().put(YADA_IDENTITY_APP, applist.get(i)).put(YADA_IDENTITY_KEYS, keys));
				}

				result.put(YADA_IDENTITY_SUB, hasheduserid);
				// a is used to get the form:
				// [{"app":app1,"grant":grant1},{"app":app1,"grant":grant2},...{"app":appN,"grant":grantN}]
				// result.put(YADA_IDENTITY_GRANTS, a);
				// aid is used to get the form:
				// [{"app":app1,"keys":[{"key":key1},{"key":key2},...{"key":keyN}]}]
				result.put(YADA_IDENTITY_GRANTS, aid);
				result.put(YADA_IDENTITY_IAT, java.time.Instant.now().getEpochSecond());
			}

		} catch (SQLException | YADAConnectionException | YADASQLException e) {
			String msg = "Unauthorized.";
			throw new YADASecurityException(msg, e);
		}
		// setIdentity(result);
		return result.toString();
	}

	/**
	 * Overrides {@link TokenValidator#validateToken()}.
	 *
	 * @throws YADASecurityException
	 *           when the {@link #DEFAULT_AUTH_TOKEN_PROPERTY} is not set
	 */

	@Override
	public void validateToken() throws YADASecurityException {
		if (null != this.getToken() && !"".equals(this.getToken())) {
			// validate token as well-formed
			try {
				JWT.require(Algorithm.HMAC512(System.getProperty(JWSKEY))).withIssuer(System.getProperty(JWTISS)).build()
				    .verify((String) this.getToken());
			} catch (UnsupportedEncodingException | JWTVerificationException exception) {
				// UTF-8 encoding not supported
				String msg = "Validation Error ";
				throw new YADASecurityException(msg, exception);
			}
		}
	}

	/**
	 * 
	 * @return
	 * @throws YADARequestException
	 * @throws YADASecurityException
	 * @throws YADAExecutionException
	 * @since 8.7.6
	 * 
	 *        check getIdentity, if not there check cache
	 * 
	 */

	public Object obtainIdentity() throws YADASecurityException, YADARequestException, YADAExecutionException {
		Object result = getCacheEntry(YADA_IDENTITY_CACHE, (String) this.getToken());
		return result;
	}

	/**
	 * Obtain specified GRANT(KEYS) from current identity
	 */
	public Object obtainGrant(String app) throws YADASecurityException, YADARequestException, YADAExecutionException {
		JSONObject jo = new JSONObject((String) getIdentity());
		JSONArray ja = jo.getJSONArray(YADA_IDENTITY_GRANTS);
		// find the app
		JSONArray keys = new JSONArray();
		for (int i = 0; i < ja.length(); i++) {
			if (app.equals(ja.getJSONObject(i).getString(YADA_IDENTITY_APP).toString())) {
				for (int ii = 0; ii < ja.getJSONObject(i).getJSONArray(YADA_IDENTITY_KEYS).length(); ii++) {
					keys.put(ja.getJSONObject(i).getJSONArray(YADA_IDENTITY_KEYS).getJSONObject(ii).getString(YADA_IDENTITY_KEY));
				}
			}
		}
		return keys;
	}

	/**
	 * @return the yadaAuthorization
	 */
	public JSONObject getYADAAuthorization() {
		return yadaAuthorization;
	}

	/**
	 * @param yadaAuthorization
	 *          the yadaAuthorization to set
	 */
	public void setYADAAuthorization(JSONObject yadaAuthorization) {
		this.yadaAuthorization = yadaAuthorization;
	}

	/**
	 * @return the resource
	 */
	public String getResource() {
		return resource;
	}

	/**
	 * @param resource
	 *          the resource to set
	 */
	public void setResource(String resource) {
		this.resource = resource;
	}

	/**
	 * @return the credentials
	 */
	public String getCredentials() {
		return credentials;
	}

	/**
	 * @param credentials
	 *          the credentials to set
	 */
	public void setCredentials(String credentials) {
		this.credentials = credentials;
	}

	/**
	 * @return the grant
	 */
	public Object getGrant() {
		return grant;
	}

	/**
	 * @param grant
	 *          the grant to set
	 */
	public void setGrant(Object grant) {
		this.grant = grant;
	}

	/**
	 * @return the identity
	 * @throws YADAExecutionException
	 * @throws YADASecurityException
	 * @throws YADARequestException
	 */
	public Object getIdentity() {
		return identity;
	}

	/**
	 * @param identity
	 *          the identity to set
	 */
	public void setIdentity(Object identity) {
		this.identity = identity;
	}

	/**
	 * @return the locks
	 */
	public JSONObject getLocks() {
		return locks;
	}

	/**
	 * @param locks
	 *          the locks to set
	 */
	public void setLocks(JSONObject locks) {
		this.locks = locks;
	}

	public ArrayList<String> getAllowList() {
		return allowList;
	}

	public void addAllowListEntry(String grant) {
		getAllowList().add(grant);
	}

	public ArrayList<String> getDenyList() {
		return denyList;
	}

	public void addDenyListEntry(String grant) {
		getDenyList().add(grant);
	}

	/**
	 * @return the app
	 */
	public String getApp() {
		return app;
	}

	/**
	 * @param app
	 *          the app to set
	 */
	public void setApp(String app) {
		this.app = app;
	}

	/**
	 * @return the result
	 */
	public String getResult() {
		return result;
	}

	/**
	 * @param result
	 *          the result to set
	 */
	public void setResult(String result) {
		this.result = result;
	}

}
