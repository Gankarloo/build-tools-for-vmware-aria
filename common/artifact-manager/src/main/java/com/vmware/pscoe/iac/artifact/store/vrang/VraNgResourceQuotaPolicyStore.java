package com.vmware.pscoe.iac.artifact.store.vrang;

/*-
 * #%L
 * artifact-manager
 * %%
 * Copyright (C) 2023 - 2024 VMware
 * %%
 * Build Tools for VMware Aria
 * Copyright 2023 VMware, Inc.
 * 
 * This product is licensed to you under the BSD-2 license (the "License"). You may not use this product except in compliance with the BSD-2 License.
 * 
 * This product may include a number of subcomponents with separate copyright notices and license terms. Your use of these subcomponents is subject to the terms and conditions of the subcomponent's license, as noted in the LICENSE file.
 * #L%
 */

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.vmware.pscoe.iac.artifact.model.Package;
import com.vmware.pscoe.iac.artifact.model.vrang.VraNgResourceQuotaPolicy;
import com.vmware.pscoe.iac.artifact.store.filters.CustomFolderFileFilter;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VraNgResourceQuotaPolicyStore extends AbstractVraNgStore {
	/**
	 * Suffix used for all the resources saved by this store.
	 */
	private static final String CUSTOM_RESOURCE_SUFFIX = ".json";
	/**
	 * Sub folder path for resource quota policy.
	 */
	private static final String RESOURCE_QUOTA_POLICY = "resource-quota";
	/**
	 * Logger.
	 */
	private final Logger logger = LoggerFactory.getLogger(VraNgResourceQuotaPolicyStore.class);

	/**
	 * Imports policies found in specified folder on server, according to filter specified in content.yml file.
	 * If there is a list of policy names - import only the specified policies.
	 * If there is no list, import everything.
	 * @param sourceDirectory the folder where policy files are stored.
	 */
	@Override
	public void importContent(File sourceDirectory) {
		logger.info("Importing files from the '{}' directory",
			com.vmware.pscoe.iac.artifact.store.vrang.VraNgDirs.DIR_POLICIES);
		// verify directory exists
		File policyFolder = Paths
			.get(sourceDirectory.getPath(),
				com.vmware.pscoe.iac.artifact.store.vrang.VraNgDirs.DIR_POLICIES,
				RESOURCE_QUOTA_POLICY)
			.toFile();
		if (!policyFolder.exists()) {
			logger.info("Resource Quota policy directory not found.");
			return;
		}

		List<String> rqPolicies = this.getItemListFromDescriptor();

		File[] resourceQuotaPolicyFiles = this.filterBasedOnConfiguration(policyFolder,
			new CustomFolderFileFilter(rqPolicies));

		if (resourceQuotaPolicyFiles != null && resourceQuotaPolicyFiles.length == 0) {
			logger.info("Could not find any Resource Quota Policies.");
			return;
		}

		logger.info("Found Resource Quota Policies. Importing ...");
		Map<String, VraNgResourceQuotaPolicy> rqPolicyOnServerByName = this.restClient.getResourceQuotaPolicies()
			.stream()
			.map(csp -> this.restClient.getResourceQuotaPolicy(csp.getId()))
			.collect(Collectors.toMap(VraNgResourceQuotaPolicy::getName, item -> item));

		for (File resourceQuotaPolicyFile : resourceQuotaPolicyFiles) {
			this.handleResourceQuotaPolicyImport(resourceQuotaPolicyFile, rqPolicyOnServerByName);
		}
	}

	/**
	 * .
	 * Handles logic to update or create a resource quota policy.
	 *
	 * @param resourceQuotaPolicyFile
	 * 
	 * @param rqPolicyOnServerByName
	 */
	private void handleResourceQuotaPolicyImport(final File resourceQuotaPolicyFile,
												 final Map<String, VraNgResourceQuotaPolicy> rqPolicyOnServerByName) {
		String rqPolicyNameWithExt = resourceQuotaPolicyFile.getName();
		String rqPolicyName = FilenameUtils.removeExtension(rqPolicyNameWithExt);
		logger.info("Attempting to import resource quota policy '{}'", rqPolicyName);
		VraNgResourceQuotaPolicy rqPolicy = jsonFileToVraNgResourceQuotaPolicy(resourceQuotaPolicyFile);
		// Check if the resource quota policy exists
		VraNgResourceQuotaPolicy existingRecord = null;
		if (rqPolicyOnServerByName.containsKey(rqPolicyName)) {
			existingRecord = rqPolicyOnServerByName.get(rqPolicyName);
		}
		//update the existing policy, is this really needed ????
		if (existingRecord != null && existingRecord.getId() != null) {
			rqPolicy.setId(existingRecord.getId());
		}
		//if the policy does not exist, and should be created
		if (existingRecord == null) {
			//if there is an id, the REST API will assume update, instead of create//may be
			//so we should remove the id to trigger create operation
			rqPolicy.setId(null);
		}

		//if the policy is to be created AND it had a project property, change the project to the current project.
		//if the policy does not have a project property, it is organization scoped  and does not need project property
		if (existingRecord == null && !rqPolicy.getProjectId().isBlank()) {
			logger.debug("Replacing policy project {}, with current project {}", rqPolicy.getProjectId(), this.restClient.getProjectId());
			rqPolicy.setProjectId(this.restClient.getProjectId());
		}


		this.restClient.createResourceQuotaPolicy(rqPolicy);
	}

	/**
	 * Converts a json catalog item file to VraNgResourceQuotaPolicy.
	 *
	 * @param jsonFile
	 *
	 * @return VraNgResourceQuotaPolicy
	 */
	private VraNgResourceQuotaPolicy jsonFileToVraNgResourceQuotaPolicy(final File jsonFile) {
		logger.debug("Converting resource quota policy file to VraNgResourceQuotaPolicies. Name: '{}'",
				jsonFile.getName());

		try (JsonReader reader = new JsonReader(new FileReader(jsonFile.getPath()))) {
			return new Gson().fromJson(reader, VraNgResourceQuotaPolicy.class);
		} catch (IOException e) {
			throw new RuntimeException(String.format("Error reading from file: %s", jsonFile.getPath()), e);
		}
	}
	/**
	 * getItemListFromDescriptor
	 * @return list of policy names to import or export.
	 */
	@Override
	protected List<String> getItemListFromDescriptor() {
		logger.info("{}->getItemListFromDescriptor", this.getClass());

		if (this.vraNgPackageDescriptor.getPolicy() == null) {
			logger.info("Descriptor policy is null");
			return null;
		} else {
			logger.info("Found items {}", this.vraNgPackageDescriptor.getPolicy().getResourceQuota());
			return this.vraNgPackageDescriptor.getPolicy().getResourceQuota();
		}
	}
	/**
	 * Exports all the content for the given project.
	 */
	@Override
	protected void exportStoreContent() {
		System.out.println(this.getClass() + "->exportStoreContent()");
		List<VraNgResourceQuotaPolicy> rqPolicies = this.restClient.getResourceQuotaPolicies();

		rqPolicies.forEach(
				policy -> {
					VraNgResourceQuotaPolicy rqPolicy = this.restClient.getResourceQuotaPolicy(policy.getId());
					storeResourceQuotaPolicyOnFilesystem(vraNgPackage, rqPolicy);
				});
	}

	/**
	 * Exports all the content for the given project based on the names in content.yaml.
	 * 
	 * @param itemNames Item names for export
	 */
	@Override
	protected void exportStoreContent(final List<String> itemNames) {
		System.out.println(this.getClass() + "->exportStoreContent({})" + itemNames.toString());
		List<VraNgResourceQuotaPolicy> rqPolicies = this.restClient.getResourceQuotaPolicies();

		rqPolicies.forEach(
				policy -> {
					if (itemNames.contains(policy.getName())) {
						VraNgResourceQuotaPolicy rqPolicy = this.restClient.getResourceQuotaPolicy(policy.getId());
						logger.info("exporting '{}'", rqPolicy.getName());
						storeResourceQuotaPolicyOnFilesystem(vraNgPackage, rqPolicy);
					}
				});
	}



	/**
	 * Store resource quota policy in JSON file.
	 *
	 * @param serverPackage        vra package
	 * @param resourceQuotaPolicy resourceQuotaPolicy representation
	 */
	private void storeResourceQuotaPolicyOnFilesystem(final Package serverPackage,
			final VraNgResourceQuotaPolicy resourceQuotaPolicy) {
		logger.debug("Storing resourceQuotaPolicy {}", resourceQuotaPolicy.getName());
		File store = new File(serverPackage.getFilesystemPath());
		File resourceQuotaPolicyFile = Paths.get(
				store.getPath(),
				com.vmware.pscoe.iac.artifact.store.vrang.VraNgDirs.DIR_POLICIES,
				RESOURCE_QUOTA_POLICY,
				resourceQuotaPolicy.getName() + CUSTOM_RESOURCE_SUFFIX).toFile();

		if (!resourceQuotaPolicyFile.getParentFile().isDirectory()
				&& !resourceQuotaPolicyFile.getParentFile().mkdirs()) {
			logger.warn("Could not create folder: {}", resourceQuotaPolicyFile.getParentFile().getAbsolutePath());
		}

		try {
			Gson gson = new GsonBuilder().setLenient().setPrettyPrinting().serializeNulls().create();
			JsonObject rqPolicyJsonObject = gson.fromJson(new Gson().toJson(resourceQuotaPolicy), JsonObject.class);
			logger.info("Created resource quota file {}",
					Files.write(Paths.get(resourceQuotaPolicyFile.getPath()),
							gson.toJson(rqPolicyJsonObject).getBytes(), StandardOpenOption.CREATE));
		} catch (IOException e) {
			logger.error("Unable to create resource quota {}", resourceQuotaPolicyFile.getAbsolutePath());
			throw new RuntimeException(
					String.format(
							"Unable to store resource quota to file %s.", resourceQuotaPolicyFile.getAbsolutePath()),
					e);
		}

	}
}
