package com.vmware.pscoe.iac.artifact;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jcraft.jsch.JSchException;
import com.vmware.pscoe.iac.artifact.cli.CliManagerVrops;
import com.vmware.pscoe.iac.artifact.cli.ZipUtilities;
import com.vmware.pscoe.iac.artifact.configuration.ConfigurationException;
import com.vmware.pscoe.iac.artifact.model.Package;
import com.vmware.pscoe.iac.artifact.model.PackageContent;
import com.vmware.pscoe.iac.artifact.model.PackageContent.Content;
import com.vmware.pscoe.iac.artifact.model.Version;
import com.vmware.pscoe.iac.artifact.model.vrops.VropsPackageDescriptor;
import com.vmware.pscoe.iac.artifact.model.vrops.VropsPackageMemberType;
import com.vmware.pscoe.iac.artifact.rest.RestClientVrops;
import com.vmware.pscoe.iac.artifact.rest.model.vrops.AlertDefinitionDTO;
import com.vmware.pscoe.iac.artifact.rest.model.vrops.AuthGroupDTO;
import com.vmware.pscoe.iac.artifact.rest.model.vrops.CustomGroupDTO;
import com.vmware.pscoe.iac.artifact.rest.model.vrops.PolicyDTO;
import com.vmware.pscoe.iac.artifact.rest.model.vrops.RecommendationDTO;
import com.vmware.pscoe.iac.artifact.rest.model.vrops.ReportDefinitionDTO;
import com.vmware.pscoe.iac.artifact.rest.model.vrops.SupermetricDTO;
import com.vmware.pscoe.iac.artifact.rest.model.vrops.SymptomDefinitionDTO;
import com.vmware.pscoe.iac.artifact.rest.model.vrops.ViewDefinitionDTO;

/**
 * This is the class that abstracts the operations for working with vROps
 * packages. In this context importing a package means importing a package file
 * into the vROps server. Exporting a package means exporting from the vROps
 * server into a file on the (local) file system. Each package is represented as
 * instance of {@link Package} class. That class contains some metadata about
 * the package, as well as a link ({@link Package#getFilesystemPath()}) to the
 * (local) file on the file system that contains the actual package content.
 * That is a path to a file.
 * 
 * @see Package
 * @see Package#getFilesystemPath()
 * @see #importAllPackages(List, boolean, boolean)
 * @see #importPackage(Package, boolean, boolean)
 * @see #exportAllPackages(List, boolean)
 * @see #exportPackage(Package, File, boolean)
 */
public class VropsPackageStore extends GenericPackageStore<VropsPackageDescriptor> {
    private final Logger logger = LoggerFactory.getLogger(VropsPackageStore.class);
    private static final String POLICY_METADATA_FILENAME = "policiesMetadata.vrops.json";
    private static final String DASHBOARD_SHARE_METADATA_FILENAME = "dashboardSharingMetadata.vrops.json";
    private static final String ACTION_SHARE = "share";
    private static final String ACTION_UNSHARE = "unshare";

    private CliManagerVrops cliManager;
    private RestClientVrops restClientVrops;

    private final File tempDir;
    private final File tempVropsExportDir;
    private final File tempVropsImportDir;
    
    public VropsPackageStore(CliManagerVrops cliManager, File tempDir) {
        this.cliManager = cliManager;
        this.tempDir = tempDir;
        tempVropsExportDir = new File(this.tempDir, "vrops-export");
        tempVropsImportDir = new File(this.tempDir, "vrops-import");
    }
    
    public VropsPackageStore(CliManagerVrops cliManager) {
        this(cliManager, createTempDirectory());
    }
    
    public VropsPackageStore(CliManagerVrops cliManager, RestClientVrops restClientVrops) {
        this(cliManager);
        this.restClientVrops = restClientVrops;
    }
    
    public VropsPackageStore(CliManagerVrops cliManager, RestClientVrops restClientVrops, Version productVersion) {
        this(cliManager);
        this.restClientVrops = restClientVrops;
        super.setProductVersion(productVersion);
    }    

    public VropsPackageStore(CliManagerVrops cliManager, RestClientVrops restClientVrops, File tempDir) {
        this.cliManager = cliManager;
        this.restClientVrops = restClientVrops;
        this.tempDir = tempDir;
        tempVropsExportDir = new File(this.tempDir, "vrops-export");
        tempVropsImportDir = new File(this.tempDir, "vrops-import");
    }

    private static File createTempDirectory() {
        try {
            return Files.createTempDirectory("iac-vrops-imp-").toFile();
        } catch (IOException ioe) {
            throw new RuntimeException("Cannot create temp directory.");
        }
    }

    @Override
    public List<Package> getPackages() {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public List<Package> exportAllPackages(List<Package> packages, boolean dryrun) {
        if (packages == null) {
            return Collections.emptyList();
        }
        for (Package pkg : packages) {
            Package exportedPkg = exportPackage(pkg, new File(pkg.getFilesystemPath()), dryrun);
            logger.info("Exported package {}", exportedPkg.getName());
        }

        return packages;
    }

	@Override
	public List<Package> importAllPackages(List<Package> pkg, boolean dryrun) {
		return this.importAllPackages(pkg, dryrun, false);
	}

	/**
     * Implement the push use case, so push the packages that are packed in the local project ot the remote vROps server and if we are not ia a dryrun mode, then
     * effectively import it into vROps.
     * @param packages Locally available packages.
     * @param dryrun   Just test the whole process without actually import the packages in vROps.
     * @return The list of pushed packages. The actual package objects may be different, for example the file content they are associated with may be different file on
     * the file system.
     */
    @Override
    public List<Package> importAllPackages(List<Package> packages, boolean dryrun, boolean mergePackages) {
        validateFilesystem(packages);
        List<Package> sourceEndpointPackages = packages;

        if (sourceEndpointPackages.isEmpty()) {
            return new ArrayList<>();
        }

        List<Package> importedPackages = new ArrayList<>();
        for (Package pkg : sourceEndpointPackages) {
            importedPackages.add(importPackage(pkg, dryrun, mergePackages));
        }

        return importedPackages;
    }

    /**
     * Implement the pull usecase, so pull all of the packages described in the {@code vropsPackageDescriptor} (constructed from content.yaml).
     * @param vropsPackage
     * @param vropsPackageDescriptor
     * @param dryrun
     * @return
     */
    @Override
    public Package exportPackage(Package vropsPackage, VropsPackageDescriptor vropsPackageDescriptor, boolean dryrun) {
        logger.info(String.format(PackageStore.PACKAGE_EXPORT, vropsPackage));
        List<String> viewNames = vropsPackageDescriptor.getView();

        if(!Files.exists(tempVropsExportDir.toPath())) {
            tempVropsExportDir.mkdir();
        }

        if (viewNames != null) {
            exportViews(vropsPackage, viewNames);
        } else {
            logger.info("No views found in content.yaml");
        }

        List<String> dashboardNames = vropsPackageDescriptor.getDashboard();
        if (dashboardNames != null) {
            exportDashboards(vropsPackage, dashboardNames);
        } else {
            logger.info("No dashboards found in content.yaml");
        }

        List<String> reportNames = vropsPackageDescriptor.getReport();
        if (reportNames != null) {
            this.exportReports(vropsPackage, reportNames);
        } else {
            logger.info("No reports found in content.yaml");
        }

        List<String> alertDefinitions = vropsPackageDescriptor.getAlertDefinition();
        if (alertDefinitions != null) {
            exportDefinitions(VropsPackageMemberType.ALERT_DEFINITION, alertDefinitions);
        } else {
            logger.info("No alert definitions found in content.yaml");
        }

        List<String> symptomDefinitions = vropsPackageDescriptor.getSymptomDefinition();
        if (symptomDefinitions != null) {
            exportDefinitions(VropsPackageMemberType.SYMPTOM_DEFINITION, symptomDefinitions);
        } else {
            logger.info("No symptom definitions found in content.yaml");
        }

        List<String> recommendations = vropsPackageDescriptor.getRecommendation();
        if (recommendations != null) {
            exportDefinitions(VropsPackageMemberType.RECOMMENDATION, recommendations);
        } else {
            logger.info("No recommendations found in content.yaml");
        }

        List<String> policies = vropsPackageDescriptor.getPolicy();
        if (policies != null) {
            exportPolicies(vropsPackage, policies);
        } else {
            logger.info("No policies found in content.yaml");
        }

        List<String> customGroupNames = vropsPackageDescriptor.getCustomGroup();
        if (customGroupNames != null) {
            exportCustomGroups(vropsPackage, customGroupNames);
        } else {
            logger.info("No custom group configurations found in content.yaml");
        }

        List<String> superMetricNames = vropsPackageDescriptor.getSuperMetric();
        if (superMetricNames != null) {
            exportSuperMetrics(vropsPackage, superMetricNames);
        } else {
            logger.info("No super metrics found in content.yaml");
        }

        List<String> metricConfigNames = vropsPackageDescriptor.getMetricConfig();
        if (metricConfigNames != null) {
            exportMetricConfigs(vropsPackage, metricConfigNames);
        } else {
            logger.info("No metric configurations found in content.yaml");
        }

        try {
            if (!dryrun) {
                File destDir = new File(vropsPackage.getFilesystemPath());
                PackageManager.copyContents(tempVropsExportDir, destDir);
            }
        } catch (IOException ioe) {
            throw new RuntimeException(String.format("Cannot copy content of downloaded and extracted package from temp dir %s to actual project dir %s : %s",
                    tempVropsExportDir.getAbsolutePath(), vropsPackage.getFilesystemPath(), ioe.getMessage()));
        } finally {
            try {
                PackageManager.cleanup(tempVropsExportDir);
                PackageManager.cleanup(tempDir);
            } catch (IOException ioe) {
                // Ignored (for now).
            }
        }

        return vropsPackage;
    }

    private void exportViews(Package vropsPackage, List<String> viewNames) {
        ViewDefinitionDTO allViewDefinitions = restClientVrops.getAllViewDefinitions();
        if (allViewDefinitions.getViewDefinitions().isEmpty()) {
            throw new RuntimeException("No views are available on vROPS server");
        }
        List<String> filteredViews = new ArrayList<>();
        for (ViewDefinitionDTO.ViewDefinition view : allViewDefinitions.getViewDefinitions()) {
            if (viewNames.stream().anyMatch(name -> this.isPackageAssetMatching(name, view.getName()))) {
                filteredViews.add(view.getName());
            }
        }

        File viewsDir = new File(tempVropsExportDir, "views");
        viewsDir.mkdir();
        logger.info("Created views temporary directory {}", viewsDir.getAbsolutePath());
        try {
            cliManager.connect();
            filteredViews.forEach(view -> copyViewToFilesystem(view, viewsDir));
        } catch (JSchException e) {
            String message = "Unable to export views in package '%s', error in connection to '%s'. '%s' : '%s' Use command '%s' for troubleshooting";
            message = String.format(message, vropsPackage.getFQName(), cliManager, e.getClass().getName(), e.getMessage(), cliManager.toSshComand());
            logger.error(message);
            throw new RuntimeException(message, e);
        } finally {
            cliManager.close();
        }
    }

    private void exportSuperMetrics(Package vropsPackage, List<String> superMetricNames) {
        SupermetricDTO allSupermetrics = restClientVrops.getAllSupermetrics();
        if (allSupermetrics.getSuperMetrics().isEmpty()) {
            throw new RuntimeException("No supermetrics are available on vROPS server");
        }
        List<String> filteredSupermetrics = new ArrayList<>();
        for (SupermetricDTO.SuperMetric supermetric : allSupermetrics.getSuperMetrics()) {
            if (superMetricNames.stream().anyMatch(name -> this.isPackageAssetMatching(name, supermetric.getName()))) {
                filteredSupermetrics.add(supermetric.getName());
            }
        }

        File superMetricsDir = new File(this.tempVropsExportDir, "supermetrics");
        if(!superMetricsDir.exists()) {
            logger.info("Created supermetrics temporary directory {}", superMetricsDir.getAbsolutePath());
            superMetricsDir.mkdir();
        }
        try {
            cliManager.connect();
            filteredSupermetrics.forEach(superMetric -> this.copySuperMetricToFilesystem(superMetric, superMetricsDir));
        } catch (JSchException e) {
            String message = "Unable to export super metrics in package '%s', error in connection to '%s'. '%s' : '%s' Use command '%s' for troubleshooting";
            message = String.format(message, vropsPackage.getFQName(), cliManager, e.getClass().getName(), e.getMessage(), cliManager.toSshComand());
            logger.error(message);
            throw new RuntimeException(message, e);
        } finally {
            cliManager.close();
        }
    }

    private void exportMetricConfigs(Package vropsPackage, List<String> metricConfigNames) {
        File metricConfigsDir = new File(this.tempVropsExportDir, "metricconfigs");
        if(!metricConfigsDir.exists()) {
            logger.info("Created metric config temporary directory {}", metricConfigsDir.getAbsolutePath());
            metricConfigsDir.mkdir();
        }

        try {
            cliManager.connect();
            for (String metricConfigName : metricConfigNames) {
                if (metricConfigName.contains(WILDCARD_MATCH_SYMBOL)) {
                    logger.warn("Unable to export metric configs with pattern '{}' as vROPs metric config export does not support regex", metricConfigName);
                } else {
                    // Could be stored with or without extension in vROps, so search for both
                    this.copyMetricConfigToFilesystem(metricConfigName, metricConfigsDir);
                    this.copyMetricConfigToFilesystem(metricConfigName + ".xml", metricConfigsDir);
                }
            }
        } catch (JSchException e) {
            String message = "Unable to export metric config in package '%s', error in connection to '%s'. '%s' : '%s' Use command '%s' for troubleshooting";
            message = String.format(message, vropsPackage.getFQName(), cliManager, e.getClass().getName(), e.getMessage(), cliManager.toSshComand());
            logger.error(message);
            throw new RuntimeException(message, e);
        } finally {
            cliManager.close();
        }
    }

    private void copyViewToFilesystem(String view, File dir) {
        try {
            File viewDir = new File(tempDir, "iac-view-" + UUID.randomUUID().toString() + "-" + System.currentTimeMillis());
            viewDir.mkdirs();
            cliManager.exportView(view, viewDir);
            File zip = new File(viewDir, view + ".zip");
            ZipUtilities.unzip(zip, viewDir);
            
            File content = new File(viewDir,"content.xml");
            moveFile(content, new File(dir, view + ".xml"));
            zip.delete();
            mergeLocalizationResources(new File(viewDir, "resources"), new File(dir, "resources"));
        } catch (IOException | JSchException e) {
            String message = "Unable to pull view '%s' from remote vROps location '%s' to local filesystem '%s' : '%s'";
            message = String.format(message, view, cliManager, e.getClass().getName(), e.getMessage());
            logger.error(message);
            throw new RuntimeException(message, e);
        }
    }

    private void copySuperMetricToFilesystem(String superMetric, File dir) {
        try {
            File superMetricDir = new File(this.tempDir, "iac-supermetric-" + UUID.randomUUID().toString() + "-" + System.currentTimeMillis());
            superMetricDir.mkdirs();
            this.cliManager.exportSuperMetric(superMetric, superMetricDir);
            File content = new File(superMetricDir,  superMetric + ".json");
            moveFile(content, new File(dir, superMetric + ".json"));
            mergeLocalizationResources(new File(superMetricDir, "resources"), new File(dir, "resources"));
        } catch (JSchException | IOException e) {
            String message = "Unable to pull super metric '%s' from remote vROps location '%s' to local filesystem '%s' : '%s'";
            message = String.format(message, superMetric, cliManager, e.getClass().getName(), e.getMessage());
            logger.error(message);
            throw new RuntimeException(message, e);
        }
    }

    private void copyMetricConfigToFilesystem(String metricConfig, File dir) {
        try {
            File metricConfigsDir = new File(this.tempDir, "iac-metricconfig-" + UUID.randomUUID().toString() + "-" + System.currentTimeMillis());
            metricConfigsDir.mkdirs();

            this.cliManager.exportMetricConfig(metricConfig, metricConfigsDir);
            File content = new File(metricConfigsDir,  metricConfig);
            if (content.exists()) {
                moveFile(content, new File(dir, metricConfig));
            }
            
        } catch (JSchException | IOException e) {
            String message = "Unable to pull metric config '%s' from remote vROps location '%s' to local filesystem '%s' : '%s'";
            message = String.format(message, metricConfig, cliManager, e.getClass().getName(), e.getMessage());
            logger.error(message);
            throw new RuntimeException(message, e);
        }
    }

    private void exportDashboards(Package vropsPackage, List<String> dashboardNames) {
        File dashboardsDir = new File(tempVropsExportDir, "dashboards");
        logger.info("Created dashboard temporary directory {} ", dashboardsDir.getAbsolutePath());
        dashboardsDir.mkdir();
        try {
            cliManager.connect();
            for (String dashboardName : dashboardNames) {
                if (dashboardName.contains(WILDCARD_MATCH_SYMBOL)) {
                    logger.warn("Unable to export dashboards with pattern '{}' as vROPs dashboard export does not support regex", dashboardName);
                } else {
                    copyDashboardToFilesystem(dashboardName, dashboardsDir);
                }
            }
            // store an initial dashboard metadata file with current dashboards
            storeDashboardSharingMetadata(dashboardsDir, dashboardNames);
        } catch (JSchException e) {
            String message = "Unable to pull dashboards '%s' from remote vROPs location to local package '%s' : '%s' : '%s' Use command '%s' for troubleshooting remote SSH connection";
            message = String.format(message, String.join(", ", dashboardNames), vropsPackage.getFQName(), e.getClass().getName(), e.getMessage(),
                    cliManager.toSshComand());
            logger.error(message);
            throw new RuntimeException(message, e);
        } finally {
            cliManager.close();
        }
    }

    private void exportReports(Package vropsPackage, List<String> reportNames) {
        ReportDefinitionDTO allReportDefinitions = restClientVrops.getAllReportDefinitions();
        if (allReportDefinitions.getReportDefinitions().isEmpty()) {
            throw new RuntimeException("No reports are available on vROPS server");
        }
        List<String> filteredReports = new ArrayList<>();
        for (ReportDefinitionDTO.ReportDefinition report : allReportDefinitions.getReportDefinitions()) {
            if (reportNames.stream().anyMatch(name -> this.isPackageAssetMatching(name, report.getName()))) {
                filteredReports.add(report.getName());
            }
        }

        File reportsDir = new File(tempVropsExportDir, "reports");
        logger.info("Created report temporaty directory {}", reportsDir.getAbsolutePath());
        reportsDir.mkdir();
        try {
            cliManager.connect();
            filteredReports.forEach(report -> copyReportToFilesystem(report, reportsDir));
        } catch (JSchException e) {
            String message = "Unable to pull reports '%s' from remote vROPs location to local package '%s' : '%s' : '%s' Use command '%s' for troubleshooting remote SSH connection";
            message = String.format(message, String.join(", ", reportNames), vropsPackage.getFQName(), e.getClass().getName(), e.getMessage(),
                    cliManager.toSshComand());
            logger.error(message);
            throw new RuntimeException(message, e);
        } finally {
            cliManager.close();
        }
    }
    
    private void importDefinitions(Package vropsPackage, File tmpDir) throws IOException {
        StringBuilder messages = new StringBuilder();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> dependentDefinitionsMap = new HashMap<>();

        List<File> symptomDefinitionFiles = addDefinitionsToImportList(tmpDir, VropsPackageMemberType.SYMPTOM_DEFINITION);
        Map<String, Object> symptomDefinitionsMap = new HashMap<>();
        for (File definitionFile : symptomDefinitionFiles) {
            String defninitionJson;
            try {
                defninitionJson = FileUtils.readFileToString(definitionFile, StandardCharsets.UTF_8);
                SymptomDefinitionDTO.SymptomDefinition symptomDefinition = mapper.readValue(defninitionJson, SymptomDefinitionDTO.SymptomDefinition.class);
                symptomDefinitionsMap.put(symptomDefinition.getId(), symptomDefinition);
            } catch (IOException e) {
                messages.append(String.format("Error reading %s : %s", VropsPackageMemberType.SYMPTOM_DEFINITION, e.getMessage()));
            } catch (Exception e) {
                messages.append(String.format("Error parsing %s : %s", VropsPackageMemberType.SYMPTOM_DEFINITION, e.getMessage()));
            }
        }
        symptomDefinitionsMap.keySet().stream().forEach(item -> {
            dependentDefinitionsMap.put(item, symptomDefinitionsMap.get(item));
        });
        restClientVrops.importDefinitionsInVrops(symptomDefinitionsMap, VropsPackageMemberType.SYMPTOM_DEFINITION, dependentDefinitionsMap);

        List<File> recommendationFiles = addDefinitionsToImportList(tmpDir, VropsPackageMemberType.RECOMMENDATION);
        Map<String, Object> recommendationsMap = new HashMap<>();
        for (File recommendationFile : recommendationFiles) {
            String recommendationJson;
            try {
                recommendationJson = FileUtils.readFileToString(recommendationFile, StandardCharsets.UTF_8);
                RecommendationDTO.Recommendation recommendation = mapper.readValue(recommendationJson, RecommendationDTO.Recommendation.class);
                recommendationsMap.put(recommendation.getId(), recommendation);
            } catch (IOException e) {
                messages.append(String.format("Error reading %s : %s", VropsPackageMemberType.RECOMMENDATION, e.getMessage()));
            } catch (Exception e) {
                messages.append(String.format("Error parsing %s : %s", VropsPackageMemberType.RECOMMENDATION, e.getMessage()));
            }
        }
        recommendationsMap.keySet().stream().forEach(item -> {
            dependentDefinitionsMap.put(item, recommendationsMap.get(item));
        });
        restClientVrops.importDefinitionsInVrops(recommendationsMap, VropsPackageMemberType.RECOMMENDATION, dependentDefinitionsMap);

        List<File> alertDefinitionFiles = addDefinitionsToImportList(tmpDir, VropsPackageMemberType.ALERT_DEFINITION);
        Map<String, Object> alertDefinitionsMap = new HashMap<>();
        for (File definitionFile : alertDefinitionFiles) {
            String defninitionJson;
            try {
                defninitionJson = FileUtils.readFileToString(definitionFile, StandardCharsets.UTF_8);
                AlertDefinitionDTO.AlertDefinition alertDefinition = mapper.readValue(defninitionJson, AlertDefinitionDTO.AlertDefinition.class);
                alertDefinitionsMap.put(alertDefinition.getId(), alertDefinition);
            } catch (IOException e) {
                messages.append(String.format("Error reading %s : %s", VropsPackageMemberType.ALERT_DEFINITION, e.getMessage()));
            } catch (Exception e) {
                messages.append(String.format("Error parsing %s : %s", VropsPackageMemberType.ALERT_DEFINITION, e.getMessage()));
            }
        }
        alertDefinitionsMap.keySet().stream().forEach(item -> {
            dependentDefinitionsMap.put(item, alertDefinitionsMap.get(item));
        });
        restClientVrops.importDefinitionsInVrops(alertDefinitionsMap, VropsPackageMemberType.ALERT_DEFINITION, dependentDefinitionsMap);

        if (messages.length() > 0) {
            throw new IOException(messages.toString());
        }
    }

    private void manageDashboardSharing(File rootDir) {
        Map<String, Map<String, List<String>>> sharingMetadata = this.getDashboardSharingMetadata(rootDir);
        sharingMetadata.keySet().forEach(action -> {
            Map<String, List<String>> shareInfo = sharingMetadata.get(action);
            if (shareInfo.keySet().isEmpty()) {
                return;
            }
            shareInfo.keySet().forEach(dashboard -> {
                List<String> groups = shareInfo.get(dashboard);
                if (groups.isEmpty()) {
                    return;
                }
                // check whether all of the groups with that dashboard will be shared/unshared
                // exist on the target vROPs
                List<String> missingGroups = this.getMissingDashboardShareGroups(groups);
                if (missingGroups != null && !missingGroups.isEmpty()) {
                    throw new RuntimeException(String.format("Unable to %s dashboard '%s' as group(s) '%s' do not exists on target vROPs", action, dashboard,
                            String.join(", ", missingGroups.toArray(new String[0]))));
                }
                switch (action) {
                    case ACTION_SHARE: {
                        logger.info("Sharing dashboard: '{}' with groups: '{}'", dashboard, String.join(", ", groups));
                        this.cliManager.shareDashboard(dashboard, groups.toArray(new String[0]));
                        break;
                    }
                    case ACTION_UNSHARE: {
                        logger.info("Unsharing dashboard: '{}' with groups: '{}'", dashboard, String.join(", ", groups));
                        this.cliManager.unshareDashboard(dashboard, groups.toArray(new String[0]));
                        break;
                    }
                    default: {
                        logger.warn("Invalid action: '{}', supported actions 'share, unshare'", action);
                    }
                }
            });
        });
    }

    private List<String> getMissingDashboardShareGroups(List<String> groups) {
        List<AuthGroupDTO> foundGroups = this.restClientVrops.findAuthGroupsByNames(groups);
        if (foundGroups == null || foundGroups.isEmpty()) {
            return groups;
        }

        return groups.stream().filter(group -> foundGroups.stream().noneMatch(t -> t.getName().equals(group))).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getPolicyMetadata(File rootDir) {
        File policiesDir = new File(rootDir.getPath(), "policies");
        if (!policiesDir.exists()) {
            return Collections.emptyMap();
        }
        String policiesMetdata = "";
        String policiesMetdataFileName = "";
        try {
            logger.info("Reading policy metadata file '{}'", POLICY_METADATA_FILENAME);
            File policiesMetadataFile = new File(policiesDir, POLICY_METADATA_FILENAME);
            policiesMetdataFileName = policiesMetadataFile.getName();
            policiesMetdata = FileUtils.readFileToString(policiesMetadataFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("An error occurred reading file {} : {}", policiesMetdataFileName, e.getMessage());

            return Collections.emptyMap();
        }
        try {
            return new ObjectMapper().readValue(policiesMetdata, HashMap.class);
        } catch (JsonProcessingException e) {
            logger.warn("An error occurred parsing metadata file {} : {}", policiesMetdataFileName, e.getMessage());

            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, List<String>>> getDashboardSharingMetadata(File rootDir) {
        // get dashboard sharing metadata
        File dashboardsDir = new File(rootDir.getPath(), "dashboards");
        if (!dashboardsDir.exists()) {
            return Collections.emptyMap();
        }
        String dashboardSharingMetadata = "";
        String dashboardSharingMetadataFileName = "";
        try {
            File dashboardShareMetadataFile = new File(dashboardsDir, DASHBOARD_SHARE_METADATA_FILENAME);
            if (!dashboardShareMetadataFile.exists()) {
                return Collections.emptyMap();
            }
            logger.info("Reading dashboard sharing metadata file '{}'", DASHBOARD_SHARE_METADATA_FILENAME);
            dashboardSharingMetadataFileName = dashboardShareMetadataFile.getName();
            dashboardSharingMetadata = FileUtils.readFileToString(dashboardShareMetadataFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(String.format("An error occurred reading file '%s'", dashboardSharingMetadataFileName), e);
        }
        try {
            return new ObjectMapper().readValue(dashboardSharingMetadata, HashMap.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(String.format("An error occurred parsing file '%s'", dashboardSharingMetadataFileName), e);
        }
    }

    private void storePolicyMetadata(File rootDir, Map<String, String> policyMetadata) {
        // generate policy metadata file
        File policyMetadataFile = new File(rootDir, POLICY_METADATA_FILENAME);
        String policyMetadataContent = "";
        try {
            policyMetadataContent = this.serializeObject(policyMetadata);
            policyMetadataContent = policyMetadataContent == null ? "" : policyMetadataContent;
            Files.write(policyMetadataFile.toPath(), policyMetadataContent.getBytes(), StandardOpenOption.CREATE_NEW);
        } catch (JsonProcessingException e) {
            String message = String.format("Error generating policy metadata file %s : %s", policyMetadataFile.getName(), e.getMessage());
            logger.error(message);

            throw new RuntimeException(message, e);
        } catch (IOException e) {
            String message = String.format("Error exporting policy metadata file %s : %s", policyMetadataFile.getName(), e.getMessage());
            logger.error(message);

            throw new RuntimeException(message, e);
        }
    }

    private void storeDashboardSharingMetadata(File rootDir, List<String> dashboards) {
        // generate dashboard sharing metadata file
        File dashboardShareMetadataFile = new File(rootDir, DASHBOARD_SHARE_METADATA_FILENAME);
        // if file exist return here and do not generate sample contents
        if (dashboardShareMetadataFile.exists()) {
            return;
        }
        String dashboardShareMetadataContent = "";
        try {
            Map<String, List<String>> dashboardInfo = new HashMap<>();
            dashboards.forEach(item -> dashboardInfo.put(item, new ArrayList<>()));

            Map<String, Map<String, List<String>>> metadata = new HashMap<>();
            metadata.put(ACTION_SHARE, dashboardInfo);
            metadata.put(ACTION_UNSHARE, dashboardInfo);

            dashboardShareMetadataContent = this.serializeObject(metadata);
            dashboardShareMetadataContent = dashboardShareMetadataContent == null ? "" : dashboardShareMetadataContent;
            logger.info("Generating dashboard sharing metadata file '{}'", DASHBOARD_SHARE_METADATA_FILENAME);
            Files.write(dashboardShareMetadataFile.toPath(), dashboardShareMetadataContent.getBytes(), StandardOpenOption.CREATE_NEW);
        } catch (JsonProcessingException e) {
            String message = String.format("Error generating dashboard sharing metadata file %s : %s", DASHBOARD_SHARE_METADATA_FILENAME, e.getMessage());
            logger.warn(message);
        } catch (IOException e) {
            String message = String.format("Error exporting dashboard sharing metadata file %s : %s", DASHBOARD_SHARE_METADATA_FILENAME, e.getMessage());
            logger.warn(message);
        }
    }

    private void importCustomGroups(Package vropsPackage, File tmpDir) {
        File customGroupsDir = new File(tmpDir.getPath(), "custom_groups");
        if (!customGroupsDir.exists()) {
            return;
        }
        // get policy metadata in order to assign correct policy to the custom group
        Map<String, String> policyMetadataMap = getPolicyMetadata(tmpDir);

        StringBuilder messages = new StringBuilder();
        for (File customGroupFile : FileUtils.listFiles(customGroupsDir, new String[] { "json" }, false)) {
            String customGroup = FilenameUtils.removeExtension(customGroupFile.getName());
            try {
                logger.info("Importing custom group: '{}'", customGroup);
                String customGroupPayload = readCustomGroupFile(customGroupFile);
                restClientVrops.importCustomGroupInVrops(customGroup, customGroupPayload, policyMetadataMap);
                logger.info("Imported custom group: '{}'", customGroup);
            } catch (Exception e) {
                messages.append(String.format("The custom group '%s' could not be imported : %s %n", customGroup, e.getMessage()));
            }
        }

        if (messages.length() > 0) {
            throw new RuntimeException(messages.toString());
        }
    }

    private String readCustomGroupFile(File customGroupFile) throws IOException {
        try {
            return FileUtils.readFileToString(customGroupFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException(String.format("An error occurred reading file %s : %s %n", customGroupFile.getName(), e.getMessage()));
        }
    }

    private void importPolicies(Package vropsPackage, File tmpDir) {
        File policiesDir = new File(tmpDir.getPath(), "policies");
        if (!policiesDir.exists()) {
            return;
        }

        StringBuilder messages = new StringBuilder();
        for (File policy : FileUtils.listFiles(policiesDir, new String[] { "zip" }, Boolean.FALSE)) {
            String policyName = FilenameUtils.removeExtension(policy.getName());
            try {
                logger.info("Importing policy: '{}'", policyName);
                restClientVrops.importPolicyFromZip(policyName, policy, Boolean.TRUE);
                logger.info("Imported policy: '{}'", policyName);
            } catch (Exception e) {
                String message = String.format("The policy '%s' could not be imported : '%s'", policyName, e.getMessage());
                logger.error(message);
                messages.append(message);
            }
        }

        if (messages.length() > 0) {
            throw new RuntimeException(messages.toString());
        }
    }

    private void exportDefinitions(VropsPackageMemberType definitionType, List<String> definitions) {
        File definitionsDir;
        String definitionsSubDir;
        switch (definitionType) {
            case ALERT_DEFINITION: {
                definitionsSubDir = "alert_definitions";
                break;
            }
            case SYMPTOM_DEFINITION: {
                definitionsSubDir = "symptom_definitions";
                break;
            }
            case RECOMMENDATION: {
                definitionsSubDir = "recommendations";
                break;
            }
            default:
                throw new RuntimeException(String.format("Defintion type %s is not supported!", definitionType));
        }
        definitionsDir = new File(tempVropsExportDir, definitionsSubDir);
        definitionsDir.mkdir();
        logger.info("Created directory '{}' for storing '{}'", definitionsDir.getAbsolutePath(), definitionType);

        Object allDefinitions = restClientVrops.exportDefinitionsFromVrops(definitionType);
        logger.info("Extracted definitions of type '{}' to '{}'", definitionType, definitionsDir.getAbsolutePath());
        Map<String, String> definitionsJsonMap = this.generateDefinitionsJsonMap(allDefinitions, definitions, definitionType);

        generateDefinitionsFile(definitionsJsonMap, definitionsDir);
    }

    private Map<String, String> generateDefinitionsJsonMap(Object definitionData, List<String> definitions, VropsPackageMemberType definitionType) {
        Map<String, String> retVal = new HashMap<>();

        if (definitionData instanceof AlertDefinitionDTO) {
            for (AlertDefinitionDTO.AlertDefinition definition : ((AlertDefinitionDTO) definitionData).getAlertDefinitions()) {
                if (definitions.stream().anyMatch(name -> this.isPackageAssetMatching(name, definition.getName()))) {
                    String payload = this.serializeObject(definition);
                    if (!StringUtils.isEmpty(payload)) {
                        retVal.put(definition.getName(), payload);
                        logger.info("Exporting definition '{}' (Type: '{}')", definition.getName(), definitionType.name());
                    }
                }
            }
        }
        if (definitionData instanceof SymptomDefinitionDTO) {
            for (SymptomDefinitionDTO.SymptomDefinition definition : ((SymptomDefinitionDTO) definitionData).getSymptomDefinitions()) {
                if (definitions.stream().anyMatch(name -> this.isPackageAssetMatching(name, definition.getName()))) {
                    String payload = this.serializeObject(definition);
                    if (!StringUtils.isEmpty(payload)) {
                        retVal.put(definition.getName(), payload);
                        logger.info("Exporting definition '{}' (Type: '{}')", definition.getName(), definitionType.name());
                    }
                }
            }
        }
        if (definitionData instanceof RecommendationDTO) {
            for (RecommendationDTO.Recommendation definition : ((RecommendationDTO) definitionData).getRecommendations()) {
                if (definitions.stream().anyMatch(name -> this.isPackageAssetMatching(name, definition.getDescription()))) {
                    String payload = this.serializeObject(definition);
                    if (!StringUtils.isEmpty(payload)) {
                        retVal.put(definition.getDescription(), payload);
                        logger.info("Exporting definition '{}' (Type: '{}')", definition.getDescription(), definitionType.name());
                    }
                }
            }
        }

        return retVal;
    }

    private void generateDefinitionsFile(Map<String, String> definitionJson, File dir) {
        for (Map.Entry<String, String> definition : definitionJson.entrySet()) {
            File definitionFile = new File(dir, definition.getKey() + ".json");
            try {
                FileUtils.write(definitionFile, definition.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.error("Error generating definitions file file '{}', message {}.", definitionFile, e.getMessage());
            }
        }
    }

    private void exportCustomGroups(Package vropsPackage, List<String> customGroupNames) {
        File customGroupTargetDir = new File(this.tempVropsExportDir, "custom_groups");
        if (!customGroupTargetDir.exists()) {
            logger.info("Created temporary directory {}", customGroupTargetDir.getAbsolutePath());
            customGroupTargetDir.mkdir();
        }

        List<CustomGroupDTO.Group> customGroups = restClientVrops.getAllCustomGroups();
        if (customGroups == null || customGroups.isEmpty()) {
            logger.error("No custom groups found in vROPs");
            return;
        }
        
        StringBuilder messages = new StringBuilder();
        for (CustomGroupDTO.Group customGroup : customGroups) {
            if (customGroupNames.stream().anyMatch(name -> this.isPackageAssetMatching(name, customGroup.getResourceKey().getName()))) {
                String payload = this.serializeObject(customGroup);
                if (!StringUtils.isEmpty(payload)) {                 
                    logger.info("Exporting custom group '{}'", customGroup.getResourceKey().getName());
                    File customGroupFile = new File(customGroupTargetDir, customGroup.getResourceKey().getName() + ".json");
                    try {
                        Files.write(customGroupFile.toPath(), payload.getBytes(), StandardOpenOption.CREATE_NEW);
                    } catch (IOException e) {
                        messages.append(String.format("Error writing file %s : %s", customGroupFile.getName(), e.getMessage()));
                    }                    
                }
            }
        }        

        if (messages.length() > 0) {
            throw new RuntimeException(messages.toString());
        }
    }

    private void exportPolicies(Package vropsPackage, List<String> policyEntries) {
        File policyDir = new File(tempVropsExportDir, "policies");
        policyDir.mkdir();
        logger.info("Created temp dir for storing policies {}", policyDir.getAbsolutePath());

        List<PolicyDTO.Policy> policies = restClientVrops.getAllPolicies();
        Map<String, String> policyIdNameMap = new HashMap<>();
        StringBuilder messages = new StringBuilder();
        for (PolicyDTO.Policy policy : policies) {
            if (policyEntries.stream().anyMatch(name -> this.isPackageAssetMatching(name, policy.getName()))) {
                policyIdNameMap.put(policy.getId(), policy.getName());
                File policyZipFile = new File(policyDir, policy.getName() + ".zip");
                try {
                    logger.info("Exporting policy '{}'", policy.getName());
                    PolicyDTO.Policy policyContent = restClientVrops.getPolicyContent(policy);
                    Files.write(policyZipFile.toPath(), policyContent.getZipFile(), StandardOpenOption.CREATE_NEW);
                    logger.info("Exported policy '{}'", policy.getName());
                } catch (IOException e) {
                    String message = String.format("Error exporting file %s : %s", policyZipFile, e.getMessage());
                    logger.error(message);
                    messages.append(message);
                }
            }
        }

        if (messages.length() > 0) {
            throw new RuntimeException(messages.toString());
        }

        // store policy metadata file (that contains id - name mapping)
        storePolicyMetadata(policyDir, policyIdNameMap);
    }

    private void copyDashboardToFilesystem(String dashboard, File dir) {
        try {
            File dashboardDir = new File(tempDir, "iac-dash-" + UUID.randomUUID().toString() + "-"
                + System.currentTimeMillis());
            dashboardDir.mkdirs();
            cliManager.exportDashboard(dashboard, dashboardDir);
            File zip = new File(dashboardDir, dashboard + ".zip");
            ZipUtilities.unzip(zip, dashboardDir);

            File subdir = new File(dashboardDir, "dashboard");
            File content = new File(subdir,  "dashboard.json");

            moveFile(content, new File(dir, dashboard + ".json"));

            zip.delete();
            mergeLocalizationResources(new File(subdir, "resources"), new File(dir, "resources"));
        } catch (IOException | JSchException e) {
            String message = String.format("Unable to pull dashboard '%s' from remote vROps location '%s' to local filesystem dir '%s' : %s : %s", dashboard,
                    cliManager, dir.getAbsolutePath(), e.getClass().getName(), e.getMessage());
            logger.error(message);
            logger.debug(message, e);
            throw new RuntimeException(message, e);
        }
    }

    private void copyReportToFilesystem(String report, File dir) {
        try {
            File reportDir = new File(tempDir, "iac-dash-" + UUID.randomUUID().toString() + "-"
                + System.currentTimeMillis());
            
            reportDir.mkdirs();
            cliManager.exportReport(report, reportDir);
            File zip = new File(reportDir, report + ".zip");
            File contentDir = new File(reportDir, report);

            ZipUtilities.unzip(zip, contentDir);
            File content = new File(reportDir,  "content.xml");
            content.renameTo(new File(contentDir, report));
            FileUtils.copyDirectory(contentDir, new File(dir, report));

            zip.delete();
        } catch (IOException | JSchException e) {
            String message = String.format("Unable to pull report '%s' from remote vROps location '%s' to local filesystem dir '%s' : %s : %s", report,
                    cliManager, dir.getAbsolutePath(), e.getClass().getName(), e.getMessage());
            logger.error(message);
            logger.debug(message, e);
            throw new RuntimeException(message, e);
        }
    }

    @Override
    public Package exportPackage(Package pkg, boolean dryrun) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public Package exportPackage(Package pkg, File exportDescriptor, boolean dryrun) {
        VropsPackageDescriptor vropsPackageDescriptor = VropsPackageDescriptor.getInstance(exportDescriptor);

        return this.exportPackage(pkg, vropsPackageDescriptor, dryrun);
    }

    @Override
    public Package importPackage(Package pkg, boolean dryrun, boolean mergePackages) {
        logger.info(String.format(PackageStore.PACKAGE_IMPORT, pkg));

        if(!Files.exists(tempVropsImportDir.toPath())) {
            tempVropsImportDir.mkdir();
        }

        File tmpDir = new File(tempVropsImportDir, "import-" + pkg.getType() + "-"
                + (pkg.getId() == null ? "0" : pkg.getId())
                + "-" + UUID.randomUUID().toString() + "-" + System.currentTimeMillis());
        logger.info("Created temporary directory {}", tmpDir.getAbsolutePath());

        try {
            new PackageManager(pkg).unpack(tmpDir);
            
            addViewToImportList(pkg, tmpDir);
            addDashboardToImportList(pkg, tmpDir);
            addReportToImportList(pkg, tmpDir);
            addSuperMetricToImportList(pkg, tmpDir);
            addMetricConfigToImportList(pkg, tmpDir);
            
            if (cliManager.hasAnyCommands()) {
                cliManager.connect();
                cliManager.importFilesToVrops();
            }
            
            importDefinitions(pkg, tmpDir);
            importPolicies(pkg, tmpDir);
            importCustomGroups(pkg, tmpDir);
            manageDashboardSharing(tmpDir);
        } catch (IOException | JSchException | ConfigurationException e) {
            String message = String.format("Unable to push package '%s' to vROps Server '%s' : %s : %s", pkg.getFQName(), cliManager, e.getClass().getName(),
                    e.getMessage());
            logger.error(message);
            logger.debug(message, e);
            throw new RuntimeException(message, e);
        } finally {
            cliManager.cleanup();
            cliManager.close();
            try {
                if(tmpDir.exists()) {
                    FileUtils.deleteDirectory(tmpDir);
                }
            } catch (IOException ioe) {
                String message = String.format("Cannot delete temporary directory '%s' : %s , leaving it there", tmpDir.getAbsolutePath(), ioe.getMessage());
                logger.warn(message);
                logger.debug(message, ioe);
            }
        }
		
        return pkg;
    }

    @Override
    protected Package deletePackage(Package pkg, boolean withContent, boolean dryrun) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    protected PackageContent getPackageContent(Package pkg) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    protected void deleteContent(Content content, boolean dryrun) {
        throw new NotImplementedException("Not implemented");
    }

    private void addViewToImportList(Package pkg, File tmp) throws IOException, ConfigurationException {
        File viewsFolder = new File(tmp.getPath(), "views");
        if (!viewsFolder.exists()) {
            return;
        }
        for (File view : FileUtils.listFiles(viewsFolder, new String[] { "xml" }, false)) {
            File viewZip = createViewZip(view);
            cliManager.addViewToImportList(viewZip);
        }
    }

    private List<File> addDefinitionsToImportList(File tmpDir, VropsPackageMemberType definitionType) {
        List<File> definitions = new ArrayList<>();
        String definitionsSubDir;
        switch (definitionType) {
            case ALERT_DEFINITION: {
                definitionsSubDir = "alert_definitions";
                break;
            }
            case SYMPTOM_DEFINITION: {
                definitionsSubDir = "symptom_definitions";
                break;
            }
            case RECOMMENDATION: {
                definitionsSubDir = "recommendations";
                break;
            }
            default:
                throw new RuntimeException(String.format("Definition type %s is not supported!", definitionType.name()));
        }
        File definitionsDir = new File(tmpDir.getPath(), definitionsSubDir);
        if (!definitionsDir.exists()) {
            return definitions;
        }
        FileUtils.listFiles(definitionsDir, new String[] { "json" }, false).stream().forEach(definitions::add);
        
        return definitions;
    }

    private void addDashboardToImportList(Package pkg, File tmp) throws IOException {
        File dashboardsFolder = new File(tmp.getPath(), "dashboards");
        if (!dashboardsFolder.exists()) {
            return;
        }
        for (File dashboard : FileUtils.listFiles(dashboardsFolder, new String[] { "json" }, false)) {
            // skip dashboard sharing metadata file
            if (dashboard.getName().contains(DASHBOARD_SHARE_METADATA_FILENAME)) {
                continue;
            }
            File dashboardZip = createDashboardZip(dashboard);
            cliManager.addDashboardToImportList(dashboardZip);
        }
    }

    private void addReportToImportList(Package pkg, File tmp) throws IOException {
        File reportsFolder = new File(tmp.getPath(), "reports");
        if (reportsFolder.exists()) {
            File[] subdirs = reportsFolder.listFiles((FileFilter) DirectoryFileFilter.DIRECTORY);
            for (File dir : subdirs) {
                File reportZip = createReportZip(dir);
                cliManager.addReportToImportList(reportZip);
            }
		}
    }

    private void addSuperMetricToImportList(Package pkg, File tmp) throws IOException {
        File superMetricsFolder = new File(tmp.getPath(), "supermetrics");
        if (!superMetricsFolder.exists()) {
            return;
        }
        for (File superMetric : FileUtils.listFiles(superMetricsFolder, new String[] { "json" }, false)) {
            this.cliManager.addSuperMetricsToImportList(superMetric);
        }
    }

    private void addMetricConfigToImportList(Package pkg, File tmp) throws IOException {
        File metricConfigsFolder = new File(tmp.getPath(), "metricconfigs");
        if (!metricConfigsFolder.exists()) {
            return;
        }
        for (File metricConfig : FileUtils.listFiles(metricConfigsFolder, null, false)) {
            this.cliManager.addMetricConfigsToImportList(metricConfig);
        }
    }

    private void mergeLocalizationResources(File srcDir, File destDir) {
        if (!srcDir.exists()) {
            return;
        }
        if (!srcDir.canRead()) {
            getLogger().warn("Cannot copy content of directory \"" + srcDir.getAbsolutePath() + "\" to \"" + destDir.getAbsolutePath()
                    + "\". Source directory is not readable. Skipping dir.");
            return;
        }
        destDir.mkdirs();
        if (!destDir.exists() || !destDir.canWrite()) {
            getLogger().warn("Cannot copy content of directory \"" + srcDir.getAbsolutePath() + "\" to \"" + destDir.getAbsolutePath()
                    + "\". Destination directory cannot be created or is not writable. Skipping dir.");
            return;
        }

        File[] resources = srcDir.listFiles();
        for (File resource : resources) {
            File destFile = new File(destDir, resource.getName());
            boolean append = destFile.exists();
            try (OutputStream os = new FileOutputStream(destFile, append)) {
                byte[] all = Files.readAllBytes(resource.toPath());
                os.write(all);
                if (all[all.length-1] != (byte)'\n') {
                    os.write('\n');
                }
            } catch (IOException ioe) {
              getLogger().warn("Error merging content of \"" + resource.getAbsolutePath() + "\" to \"" + destFile.getAbsolutePath() + "\" : " + ioe.getLocalizedMessage()
                      + "; Continuing with other resource files if any.");
            }
        }
    }

    private static void moveFile(File source, File dest) throws IOException {
        if (!source.exists()) {
            throw new IOException("Source file \"" + source.getAbsolutePath() + "\" does not exist. Cannot move/rename it to \"" + dest.getAbsolutePath() + "\".");
        }
        boolean success = source.renameTo(dest);
        if (!success) {
            FileUtils.copyFile(source, dest);
            source.delete();
        }
    }

    private File createViewZip(File view) throws ConfigurationException, IOException {
        String name = FilenameUtils.removeExtension(view.getName());
        logger.info("View: {}", name);
        File viewZip = new File(view.getParent(), name + ".zip");
        try (
                FileOutputStream fos = new FileOutputStream(viewZip);
                ZipOutputStream zipOut = new ZipOutputStream(fos);)
        {
            ZipEntry contentZipEntry = new ZipEntry("content.xml");
            zipOut.putNextEntry(contentZipEntry);
            String id = getViewId(view);
            fileCopyAndCloseInput(view, zipOut);
            boolean success = view.delete();
            if (!success) {
                logger.warn("Error deleting view file '{}'. Leaving file there.", view.getAbsolutePath());
            }

            File resourcesDir = new File(view.getParent(), "resources");
            if (resourcesDir.exists()) {
                for (File propfile : FileUtils.listFiles(resourcesDir, new String[] { "properties" }, false)) {
                    ZipEntry propEntry = new ZipEntry("resources/" + propfile.getName());
                    zipOut.putNextEntry(propEntry);
                    fileCopyFiltering(propfile, "view." + id, zipOut);
                }
            }
        }
        return viewZip;
    }

    private File createDashboardZip(File dashboard) throws IOException {
        logger.info("Dashboard absolute path: {}", dashboard.getAbsolutePath());
        String name = FilenameUtils.removeExtension(dashboard.getName());
        logger.info("Dashboard name {}", name);
        File dashboardZip = new File(dashboard.getParent(),  name + ".zip");
        try (
                FileOutputStream fos = new FileOutputStream(dashboardZip);
                ZipOutputStream zipOut = new ZipOutputStream(fos);)
        {
            ZipEntry contentZipEntry = new ZipEntry("dashboard/dashboard.json");
            zipOut.putNextEntry(contentZipEntry);
            fileCopyAndCloseInput(dashboard, zipOut);
            boolean success = dashboard.delete();
            if (!success) {
                logger.warn("Error deleting dashboard file '{}'. Leaving file there.", dashboard.getAbsolutePath());
            }

            File resourcesDir = new File(dashboard.getParent(), "resources");
            if(resourcesDir.exists()) {
                logger.info("Resources directory: {}", resourcesDir.getAbsolutePath());
                for (File propfile : FileUtils.listFiles(resourcesDir, new String[]{"properties"}, false)) {
                    ZipEntry propEntry = new ZipEntry("dashboard/resources/" + propfile.getName());
                    zipOut.putNextEntry(propEntry);
                    fileCopyFiltering(propfile, name, zipOut);
                }
            }
        }
        return dashboardZip;
    }

    private File createReportZip(File report) throws IOException {
        logger.info("Report absolute path: {}", report.getAbsolutePath());
        String name = FilenameUtils.removeExtension(report.getName());
        logger.info("Report name {} ", name);
        File reportZip = new File(report.getParent(),  name + ".zip");
        try (
                FileOutputStream fos = new FileOutputStream(reportZip);
                ZipOutputStream zipOut = new ZipOutputStream(fos);)
        {
            ZipEntry contentZipEntry = new ZipEntry("content.xml");
            zipOut.putNextEntry(contentZipEntry);
            fileCopyAndCloseInput(new File(report, "content.xml"), zipOut);
            boolean success = report.delete();
            if (!success) {
                logger.warn("Error deleting dashboard file '{}'. Leaving file there.", report.getAbsolutePath());
            }

            File resourcesDir = new File(report, "resources");
            if(resourcesDir.exists()) {
                logger.info("Resources directory: {}", resourcesDir.getAbsolutePath());
                for (File resource : FileUtils.listFiles(resourcesDir, new String[]{"properties"}, false)) {
                    ZipEntry propEntry = new ZipEntry("resources/" + resource.getName());
                    zipOut.putNextEntry(propEntry);
                    fileCopyFiltering(resource, name, zipOut);
                }
            }
        }
        return reportZip;
    }

    private static int fileCopyAndCloseInput(File in, OutputStream out) throws IOException{
        try (FileInputStream is = new FileInputStream(in)) {
            return IOUtils.copy(is, out);
        }
    }

    private String serializeObject(Object object) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            return mapper.writeValueAsString(object);
        } catch (Exception e) {
            logger.warn(String.format("Error serializing %s : %s", object.toString(), e.getMessage()));
        }

        return null;
    }

    public static void fileCopyFiltering(File propFile, String prefix, OutputStream out) throws IOException {
        Properties filtered  = new Properties();
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propFile);){
            props.load(in);
            props.forEach((key, value) ->  {
                if ((""+key).startsWith(prefix)) {
                    filtered.setProperty("" + key, "" + value);
                }
            });
        }
        filtered.store(out, null);
    }

    public static String getViewId(File view) throws ConfigurationException, IOException {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = null;
        Document doc = null;
        try {
            dBuilder = dbFactory.newDocumentBuilder();
            doc = dBuilder.parse(view);
        } catch (ParserConfigurationException e) {
            throw new ConfigurationException("The view file \"" + view.getAbsolutePath() + "\" cannot be parsed as XML file because of serious configuration error "
                    + e.getLocalizedMessage(), e);
        } catch (SAXException e) {
            throw new ConfigurationException("The view file \"" + view.getAbsolutePath() + "\" has invalid format. It is not a correct XML file. "
                    + e.getLocalizedMessage(), e);
        }
        Element root = doc.getDocumentElement();
        if (!"Content".equalsIgnoreCase(root.getNodeName())) {
            throw new ConfigurationException("The view file \"" + view.getAbsolutePath() + "\" is not in the expected format. It is a valid XML file, bu the root "
                    + "element is \"" + root.getNodeName() + "\" while the expected one is \"Content\".");
        }
        NodeList views = root.getChildNodes();
        if (views.getLength() == 0) {
            throw new ConfigurationException("The view file \"" + view.getAbsolutePath()
                    + "\" is not in the expected format. The XML \"Content\" element does not have any children. Exactly one with name \"Views\" is expected.");
        }
        Node viewsNode = root.getFirstChild();
        while (viewsNode != null && "#text".equalsIgnoreCase(viewsNode.getNodeName())) {
            viewsNode = viewsNode.getNextSibling();
        }
        if (viewsNode == null || !"Views".equalsIgnoreCase(viewsNode.getNodeName())) {
            throw new ConfigurationException("The view file \"" + view.getAbsolutePath() + "\" is not in the expected format. First child element under \"Content\" is "
                    + ( viewsNode == null ? "UNKNOWN" : "\"" + viewsNode.getNodeName() + "\"" ) + ", expected: \"Views\".");
        }
        Node viewDefNode = viewsNode.getFirstChild();
        while (viewDefNode != null && "#text".equalsIgnoreCase(viewDefNode.getNodeName())) {
            viewDefNode = viewDefNode.getNextSibling();
        }
        if (viewDefNode == null || !"ViewDef".equalsIgnoreCase(viewDefNode.getNodeName())) {
            throw new ConfigurationException("The view file \"" + view.getAbsolutePath() + "\" has invalid format. Child node under \"Content\" -> \"Views\" is "
                    + (viewDefNode == null ? "NOT PRESENT" : "\"" + viewDefNode + "\"") + ", expected \"ViewDef\"");
        }
        NamedNodeMap attributes = viewDefNode.getAttributes();
        Node id = attributes.getNamedItem("id");
        if (id == null || id.getNodeValue().trim().length() == 0) {
            throw new ConfigurationException("The view file \"" + view.getAbsolutePath()
                    + "\" has invalid format. No XML id attribute available for \"Content\" - > \"Views\" -> " + "\"ViewDef\".");
        }
        return id.getNodeValue().trim();
    }

}