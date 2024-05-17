package com.vmware.pscoe.iac.artifact.configuration;

/*
 * #%L
 * artifact-manager
 * %%
 * Copyright (C) 2023 VMware
 * %%
 * Build Tools for VMware Aria
 * Copyright 2023 VMware, Inc.
 * 
 * This product is licensed to you under the BSD-2 license (the "License"). You may not use this product except in compliance with the BSD-2 License.  
 * 
 * This product may include a number of subcomponents with separate copyright notices and license terms. Your use of these subcomponents is subject to the terms and conditions of the subcomponent's license, as noted in the LICENSE file.
 * #L%
 */

import java.net.URISyntaxException;
import java.util.Properties;

import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.vmware.pscoe.iac.artifact.model.PackageType;

/**
 * Contains properties common for all configurations.
 */
public abstract class Configuration {

	/**
	 * The type of the package.
	 * Can be: `vro`, `vra-ng`, etc
	 *
	 * @param type
	 */
	private final PackageType type;

	// Important - Maven base projects (maven/base-package/**/pom.xml)
	// configurations must be compatible with @Configuration and all its subclasses
	/**
	 * Hostname of the server, without the protocol.
	 *
	 * @param HOST 
	 */
	public static final String HOST = "host";

	/**
	 * Port of the server.
	 *
	 * @param PORT
	 */
	public static final String PORT = "port";

	/**
	 * Username to authenticate with.
	 *
	 * @param USERNAME
	 */
	public static final String USERNAME = "username";

	/**
	 * Password to authenticate with.
	 *
	 * @param PASSWORD
	 */
	public static final String PASSWORD = "password";

	/**
	 * Connection timeout in seconds.
	 *
	 * @param CONNECTION_TIMEOUT
	 */
	public static final String CONNECTION_TIMEOUT = "vrealize.connection.timeout";

	/**
	 * Socket timeout in seconds.
	 *
	 * @param SOCKET_TIMEOUT
	 */
	public static final String SOCKET_TIMEOUT = "vrealize.socket.timeout";

	/**
	 * Default connection timeout in seconds.
	 *
	 * @param DEFAULT_CONNECTION_TIMEOUT
	 */
	public static final Integer DEFAULT_CONNECTION_TIMEOUT = 360;

	/**
	 * Default socket timeout in seconds.
	 *
	 * @param DEFAULT_SOCKET_TIMEOUT
	 */
	public static final Integer DEFAULT_SOCKET_TIMEOUT = 360;

	/**
	 * Strategy configuration property. If set to true will perform force import
	 * regardless of the vRO server content.
	 *
	 * NOTE: This strategy is used for pushing and pulling.
	 */
	public static final String IMPORT_OLD_VERSIONS = "importOldVersions";

	/**
	 * Strategy configuration property. If set to true will only import a
	 * package if it's the same or newer version than the one in the vRO server.
	 * The difference between this strategy and the default one is that this one will throw an error
	 * failing the pipeline.
	 *
	 * NOTE: This is only used during pushing
	 */
	public static final String FORCE_IMPORT_LATEST_VERSIONS = "forceImportLatestVersions";

	/**
	 * Contains all the properties passed by the user
	 *
	 * @param properties
	 */
	protected Properties properties;

	/**
	 * Logger instance
	 */
	protected final Logger logger = LoggerFactory.getLogger(Configuration.class);;

	protected Configuration(PackageType type, Properties props) {
		this.type = type;
		this.properties = props;
	}

	/**
	 * @return the package type
	 */
	public PackageType getPackageType() {
		return type;
	}

	/**
	 * @return the host
	 */
	public String getHost() {
		return this.properties.getProperty(HOST);
	}

	/**
	 * @return the port
	 */
	public int getPort() {
		try {
			return Integer.parseInt(this.properties.getProperty(PORT));
		} catch (NumberFormatException e) {
			throw new RuntimeException("Port is not a number");
		}
	}

	/**
	 * Will return the username without the domain.
	 *
	 * @return the username
	 */
	public String getUsername() {
		String username = this.properties.getProperty(USERNAME);
		return StringUtils.isEmpty(username) ? username
				: (username.indexOf("@") > 0 ? username.substring(0, username.lastIndexOf("@")) : username);
	}

	/**
	 * Will return the domain from the username if it exists.
	 *
	 * @return the domain
	 */
	public String getDomain() {
		String username = this.properties.getProperty(USERNAME);
		return StringUtils.isEmpty(username) ? username
				: (username.indexOf("@") > 0 ? username.substring(username.lastIndexOf("@") + 1) : null);
	}

	/**
	 * @return the password
	 */
	public String getPassword() {
		return this.properties.getProperty(PASSWORD);
	}

	/**
	 * @return a boolean value indicating if old versions should be imported
	 */
	public boolean isImportOldVersions() {
		return Boolean.parseBoolean(this.properties.getProperty(IMPORT_OLD_VERSIONS));
	}

	/**
	 * @return a boolean value indicating if the latest versions should be enforced
	 */
	public boolean isForceImportLatestVersions() {
		return Boolean.parseBoolean(this.properties.getProperty(FORCE_IMPORT_LATEST_VERSIONS));
	}

	/**
	 * Perform validation on the configuration.
	 */
	public void validate(boolean domainOptional) throws ConfigurationException {
		validate(domainOptional, false);
	}

	/**
	 * Perform validation on the configuration.
	 */
	public void validate(boolean domainOptional, boolean useRefreshTokenForAuthentication)
			throws ConfigurationException {
		StringBuilder message = new StringBuilder();
		if (StringUtils.isEmpty(getHost())) {
			message.append("Hostname ");
		}
		if (StringUtils.isEmpty(getPort())) {
			message.append("Port ");
		}
		if (StringUtils.isEmpty(getDomain()) && !domainOptional) {
			message.append("Domain (in username) ");
		}
		if (!useRefreshTokenForAuthentication) {

			logger.info("Refresh token not detected. Checking username and password on configuration");
			if (StringUtils.isEmpty(getUsername())) {
				message.append("Username ");
			}
			if (StringUtils.isEmpty(getPassword())) {
				message.append("Password ");
			}

		}
		if (message.length() != 0) {
			throw new ConfigurationException("Configuration validation failed: Empty " + message);
		}

		try {
			new URIBuilder().setScheme("https").setHost(getHost()).setPort(getPort()).build();
		} catch (URISyntaxException e) {
			throw new ConfigurationException(e.getMessage(), e);
		}
	}
}
