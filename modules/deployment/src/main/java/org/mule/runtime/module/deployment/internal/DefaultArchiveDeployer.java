/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.deployment.internal;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static org.apache.commons.collections.CollectionUtils.collect;
import static org.apache.commons.collections.CollectionUtils.find;
import static org.apache.commons.io.FileUtils.deleteDirectory;
import static org.apache.commons.lang3.StringUtils.removeEndIgnoreCase;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.container.api.MuleFoldersUtil.getAppDataFolder;
import static org.mule.runtime.core.api.util.ExceptionUtils.containsType;
import static org.mule.runtime.core.internal.util.splash.SplashScreen.miniSplash;
import static org.mule.runtime.module.deployment.impl.internal.util.DeploymentPropertiesUtils.resolveDeploymentProperties;
import org.mule.runtime.deployment.model.api.DeployableArtifact;
import org.mule.runtime.deployment.model.api.DeploymentException;
import org.mule.runtime.deployment.model.api.DeploymentStartException;
import org.mule.runtime.module.deployment.api.DeploymentListener;
import org.mule.runtime.module.deployment.impl.internal.artifact.AbstractDeployableArtifactFactory;
import org.mule.runtime.module.deployment.impl.internal.artifact.ArtifactFactory;
import org.mule.runtime.module.deployment.impl.internal.artifact.MuleContextListenerFactory;
import org.mule.runtime.module.deployment.internal.util.ObservableList;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.apache.commons.beanutils.BeanPropertyValueEqualsPredicate;
import org.apache.commons.beanutils.BeanToPropertyValueTransformer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deployer of an artifact within mule container. - Keeps track of deployed artifacts - Avoid already deployed artifacts to be
 * redeployed - Deploys, undeploys, redeploys packaged and exploded artifacts
 */
public class DefaultArchiveDeployer<T extends DeployableArtifact> implements ArchiveDeployer<T> {

  public static final String ARTIFACT_NAME_PROPERTY = "artifactName";
  public static final String JAR_FILE_SUFFIX = ".jar";
  public static final String ZIP_FILE_SUFFIX = ".zip";
  private static final Logger logger = LoggerFactory.getLogger(DefaultArchiveDeployer.class);

  private final ArtifactDeployer<T> deployer;
  private final ArtifactArchiveInstaller artifactArchiveInstaller;
  private final Map<String, ZombieArtifact> artifactZombieMap = new HashMap<>();
  private final File artifactDir;
  private final ObservableList<T> artifacts;
  private final ArtifactDeploymentTemplate deploymentTemplate;
  private AbstractDeployableArtifactFactory<T> artifactFactory;
  private DeploymentListener deploymentListener = new NullDeploymentListener();
  private MuleContextListenerFactory muleContextListenerFactory;


  public DefaultArchiveDeployer(final ArtifactDeployer deployer,
                                final AbstractDeployableArtifactFactory artifactFactory,
                                final ObservableList<T> artifacts,
                                ArtifactDeploymentTemplate deploymentTemplate,
                                MuleContextListenerFactory muleContextListenerFactory) {
    this.deployer = deployer;
    this.artifactFactory = artifactFactory;
    this.artifacts = artifacts;
    this.deploymentTemplate = deploymentTemplate;
    this.artifactDir = artifactFactory.getArtifactDir();
    this.artifactArchiveInstaller = new ArtifactArchiveInstaller(artifactDir);
    this.muleContextListenerFactory = muleContextListenerFactory;
  }

  @Override
  public boolean isUpdatedZombieArtifact(String artifactName) {
    @SuppressWarnings("rawtypes")
    Collection<String> deployedAppNames = collect(artifacts, new BeanToPropertyValueTransformer(ARTIFACT_NAME_PROPERTY));

    if (deployedAppNames.contains(artifactName) && (!artifactZombieMap.containsKey(artifactName))) {
      return false;
    }

    // First get saved zombieFile
    ZombieArtifact zombieArtifact = artifactZombieMap.get(artifactName);

    if ((zombieArtifact != null) && (!zombieArtifact.updatedZombieApp())) {
      return false;
    }

    return true;
  }

  @Override
  public void undeployArtifact(String artifactId) {
    this.undeployArtifact(artifactId, true);
  }

  private void undeployArtifact(String artifactId, boolean removeData) {
    ZombieArtifact zombieArtifact = artifactZombieMap.get(artifactId);
    if ((zombieArtifact != null)) {
      if (zombieArtifact.exists()) {
        return;
      } else {
        artifactZombieMap.remove(artifactId);
      }

    }

    T artifact = findArtifact(artifactId);
    undeploy(artifact, removeData);
  }

  @Override
  public File getDeploymentDirectory() {
    return artifactFactory.getArtifactDir();
  }

  private File installArtifact(URI artifactAchivedUri) throws IOException {
    File artifactLocation;
    try {
      artifactLocation = installFrom(artifactAchivedUri);
    } catch (Throwable t) {
      File artifactArchive = new File(artifactAchivedUri);
      String artifactName = removeEndIgnoreCase(artifactArchive.getName(), JAR_FILE_SUFFIX);

      // error text has been created by the deployer already
      logDeploymentFailure(t, artifactName);

      addZombieFile(artifactName, artifactArchive);

      deploymentListener.onDeploymentFailure(artifactName, t);

      throw t;
    }
    return artifactLocation;
  }

  private void logDeploymentFailure(Throwable t, String artifactName) {
    if (containsType(t, DeploymentStartException.class)) {
      logger.error(miniSplash(format("Failed to deploy artifact '%s', see artifact's log for details", artifactName)));
      logger.error(t.getMessage());
    } else {
      logger.error(miniSplash(format("Failed to deploy artifact '%s', see below", artifactName)), t);
    }
  }

  @Override
  public Map<String, Map<URI, Long>> getArtifactsZombieMap() {
    Map<String, Map<URI, Long>> result = new HashMap<>();
    for (String artifact : artifactZombieMap.keySet()) {
      Map<URI, Long> tmpMap = new HashMap<>();
      ZombieArtifact zombieArtifact = artifactZombieMap.get(artifact);
      for (Map.Entry<File, Long> file : zombieArtifact.initialResourceFiles.entrySet()) {
        tmpMap.put(file.getKey().toURI(), file.getValue());
      }
      result.put(artifact, tmpMap);
    }
    return result;
  }

  @Override
  public void setArtifactFactory(final ArtifactFactory<T> artifactFactory) {
    if (!(artifactFactory instanceof AbstractDeployableArtifactFactory)) {
      throw new IllegalArgumentException("artifactFactory is expected to be of type "
          + AbstractDeployableArtifactFactory.class.getName());
    }
    this.artifactFactory = (AbstractDeployableArtifactFactory<T>) artifactFactory;
  }

  @Override
  public void undeployArtifactWithoutUninstall(T artifact) {
    logRequestToUndeployArtifact(artifact);
    try {
      deploymentListener.onUndeploymentStart(artifact.getArtifactName());
      deployer.undeploy(artifact);
      deploymentListener.onUndeploymentSuccess(artifact.getArtifactName());
    } catch (DeploymentException e) {
      deploymentListener.onUndeploymentFailure(artifact.getArtifactName(), e);
      throw e;
    }
  }

  ArtifactDeployer getDeployer() {
    return deployer;
  }

  @Override
  public void setDeploymentListener(DeploymentListener deploymentListener) {
    this.deploymentListener = deploymentListener;
  }

  private T deployExplodedApp(String addedApp, Optional<Properties> deploymentProperties) throws DeploymentException {
    if (logger.isInfoEnabled()) {
      logger.info("================== New Exploded Artifact: " + addedApp);
    }

    T artifact;
    try {
      File artifactLocation = new File(artifactDir, addedApp);
      artifact = createArtifact(artifactLocation, deploymentProperties);

      // add to the list of known artifacts first to avoid deployment loop on failure
      trackArtifact(artifact);
    } catch (Throwable t) {
      final File artifactDir1 = artifactDir;
      File artifactDir = new File(artifactDir1, addedApp);

      addZombieFile(addedApp, artifactDir);

      if (containsType(t, DeploymentStartException.class)) {
        logger.error(miniSplash(format("Failed to deploy artifact '%s', see artifact's log for details", addedApp)));
        logger.error(t.getMessage());
      } else {
        logger.error(miniSplash(format("Failed to deploy artifact '%s', see below", addedApp)), t);
      }

      deploymentListener.onDeploymentFailure(addedApp, t);

      if (t instanceof DeploymentException) {
        throw (DeploymentException) t;
      } else {
        throw new DeploymentException(createStaticMessage("Failed to deploy artifact: " + addedApp), t);
      }
    }

    deployArtifact(artifact, deploymentProperties);
    return artifact;
  }

  private void addZombieApp(DeployableArtifact artifact) {
    if (allResourcesExist(artifact.getResourceFiles())) {
      try {
        List<File> resourceFiles = new ArrayList<>();
        resourceFiles.addAll(asList(artifact.getResourceFiles()));
        if (artifact.getDescriptor().isRedeploymentEnabled()) {
          resourceFiles.add(artifact.getDescriptor().getDescriptorFile());
        }
        artifactZombieMap.put(artifact.getArtifactName(),
                              new ZombieArtifact(resourceFiles.toArray(new File[resourceFiles.size()])));
      } catch (Exception e) {
        // ignore resource
      }
    }
  }

  private void addZombieFile(String artifactName, File marker) {
    // no sync required as deploy operations are single-threaded
    if (marker == null) {
      return;
    }

    if (!marker.exists()) {
      return;
    }
    try {
      artifactZombieMap.put(artifactName, new ZombieArtifact(new File[] {marker}));
    } catch (Exception e) {
      logger.debug(format("Failed to mark an exploded artifact [%s] as a zombie", marker.getName()), e);
    }
  }

  private T findArtifact(String artifactName) {
    return (T) find(artifacts, new BeanPropertyValueEqualsPredicate(ARTIFACT_NAME_PROPERTY, artifactName));
  }

  private void trackArtifact(T artifact) {
    preTrackArtifact(artifact);

    artifacts.add(artifact);
  }

  public void preTrackArtifact(T artifact) {
    T previousArtifact = findArtifact(artifact.getArtifactName());
    artifacts.remove(previousArtifact);
  }

  private void undeploy(T artifact, boolean removeData) {
    logRequestToUndeployArtifact(artifact);
    try {
      deploymentListener.onUndeploymentStart(artifact.getArtifactName());

      artifacts.remove(artifact);
      deployer.undeploy(artifact);
      artifactArchiveInstaller.uninstallArtifact(artifact.getArtifactName());
      if (removeData) {
        final File dataFolder = getAppDataFolder(artifact.getDescriptor().getDataFolderName());
        try {
          deleteDirectory(dataFolder);
        } catch (IOException e) {
          logger.warn(
                      format("Cannot delete data folder '%s' while undeploying artifact '%s'. This could be related to some files still being used and can cause a memory leak",
                             dataFolder, artifact.getArtifactName()),
                      e);
        }
      }
      deploymentListener.onUndeploymentSuccess(artifact.getArtifactName());

      logArtifactUndeployed(artifact);
    } catch (RuntimeException e) {
      deploymentListener.onUndeploymentFailure(artifact.getArtifactName(), e);
      throw e;
    }
  }

  private void logRequestToUndeployArtifact(T artifact) {
    if (logger.isInfoEnabled()) {
      logger.info("================== Request to Undeploy Artifact: " + artifact.getArtifactName());
    }
  }

  private void logArtifactUndeployed(T artifact) {
    if (logger.isInfoEnabled()) {
      logger.info(miniSplash(format("Undeployed artifact '%s'", artifact.getArtifactName())));
    }
  }

  private File installFrom(URI uri) throws IOException {
    return artifactArchiveInstaller.installArtifact(uri);
  }

  private T createArtifact(File artifactLocation, Optional<Properties> appProperties) throws IOException {
    T artifact = artifactFactory.createArtifact(artifactLocation, appProperties);
    artifact.setMuleContextListener(muleContextListenerFactory.create(artifact.getArtifactName()));
    return artifact;
  }

  private static boolean allResourcesExist(File[] resourceFiles) {
    return stream(resourceFiles).allMatch(File::exists);
  }

  private static class ZombieArtifact {

    Map<File, Long> initialResourceFiles = new HashMap<>();

    private ZombieArtifact(File[] resourceFiles) {
      // Is exploded artifact
      for (File resourceFile : resourceFiles) {
        initialResourceFiles.put(resourceFile, resourceFile.lastModified());
      }
    }

    public boolean isFor(URI uri) {
      return initialResourceFiles.entrySet().stream().anyMatch((entry) -> entry.getKey().toURI().equals(uri));
    }

    public boolean updatedZombieApp() {
      return initialResourceFiles.entrySet().stream()
          .anyMatch((entry) -> !entry.getValue().equals(entry.getKey().lastModified()));
    }

    // Returns true only if all the files exist
    public boolean exists() {
      return allResourcesExist(initialResourceFiles.keySet().toArray(new File[initialResourceFiles.size()]));
    }

  }

  @Override
  public T deployPackagedArtifact(String zip, Optional<Properties> deploymentProperties) throws DeploymentException {
    URI uri;
    File artifactZip;
    try {
      final String artifactName = removeEndIgnoreCase(zip, JAR_FILE_SUFFIX);
      artifactZip = new File(artifactDir, zip);
      uri = artifactZip.toURI();
      return deployOrRedeployPackagedArtifact(uri, artifactName, deploymentProperties);
    } catch (DeploymentException e) {
      throw e;
    } catch (Exception e) {
      throw new DeploymentException(createStaticMessage("Failed to deploy from zip: " + zip), e);
    }
  }

  @Override
  public T deployPackagedArtifact(URI artifactAchivedUri, Optional<Properties> appProperties)
      throws DeploymentException {
    final String artifactName = removeEndIgnoreCase(new File(artifactAchivedUri).getName(), JAR_FILE_SUFFIX);
    return deployOrRedeployPackagedArtifact(artifactAchivedUri, artifactName, appProperties);
  }

  @Override
  public void redeploy(T artifact, Optional<Properties> deploymentProperties) throws DeploymentException {
    if (logger.isInfoEnabled()) {
      logger.info(miniSplash(format("Redeploying artifact '%s'", artifact.getArtifactName())));
    }

    deploymentListener.onRedeploymentStart(artifact.getArtifactName());

    deploymentTemplate.preRedeploy(artifact);

    if (!artifactZombieMap.containsKey(artifact.getArtifactName())) {
      deploymentListener.onUndeploymentStart(artifact.getArtifactName());
      try {
        deployer.undeploy(artifact);
        deploymentListener.onUndeploymentSuccess(artifact.getArtifactName());
      } catch (Throwable e) {
        deploymentListener.onUndeploymentFailure(artifact.getArtifactName(), e);
        deploymentListener.onRedeploymentFailure(artifact.getArtifactName(), e);
      }
    }

    deploymentListener.onDeploymentStart(artifact.getArtifactName());
    try {
      artifact = createArtifact(artifact.getLocation(),
                                ofNullable(resolveDeploymentProperties(artifact.getDescriptor().getDataFolderName(),
                                                                       deploymentProperties)));
      trackArtifact(artifact);

      deployer.deploy(artifact);
      artifactArchiveInstaller.createAnchorFile(artifact.getArtifactName());
      deploymentListener.onDeploymentSuccess(artifact.getArtifactName());
      deploymentTemplate.postRedeploy(artifact);
      deploymentListener.onRedeploymentSuccess(artifact.getArtifactName());
    } catch (Throwable t) {
      try {
        logDeploymentFailure(t, artifact.getArtifactName());
        addZombieApp(artifact);
        if (t instanceof DeploymentException) {
          throw (DeploymentException) t;
        }
        String msg = "Failed to deploy artifact: " + artifact.getArtifactName();
        throw new DeploymentException(createStaticMessage(msg), t);
      } finally {
        deploymentListener.onDeploymentFailure(artifact.getArtifactName(), t);
        deploymentListener.onRedeploymentFailure(artifact.getArtifactName(), t);
      }
    }

    artifactZombieMap.remove(artifact.getArtifactName());
  }

  @Override
  public void deployArtifact(T artifact, Optional<Properties> deploymentProperties) throws DeploymentException {
    try {
      // add to the list of known artifacts first to avoid deployment loop on failure
      trackArtifact(artifact);

      deploymentListener.onDeploymentStart(artifact.getArtifactName());
      deployer.deploy(artifact);

      artifactArchiveInstaller.createAnchorFile(artifact.getArtifactName());
      deploymentListener.onDeploymentSuccess(artifact.getArtifactName());
      artifactZombieMap.remove(artifact.getArtifactName());
    } catch (Throwable t) {
      // error text has been created by the deployer already
      if (containsType(t, DeploymentStartException.class)) {
        logger.error(miniSplash(format("Failed to deploy artifact '%s', see artifact's log for details",
                                       artifact.getArtifactName())));
        logger.error(t.getMessage());
      } else {
        logger.error(miniSplash(format("Failed to deploy artifact '%s', see below", artifact.getArtifactName())), t);
      }

      addZombieApp(artifact);

      deploymentListener.onDeploymentFailure(artifact.getArtifactName(), t);
      if (t instanceof DeploymentException) {
        throw (DeploymentException) t;
      } else {
        throw new DeploymentException(createStaticMessage("Failed to deploy artifact: " + artifact.getArtifactName()), t);
      }
    }
  }

  private T deployOrRedeployPackagedArtifact(final URI artifactUri, String artifactName,
                                             Optional<Properties> deploymentProperties) {
    ZombieArtifact zombieArtifact = artifactZombieMap.get(artifactName);
    if (zombieArtifact != null) {
      if (zombieArtifact.isFor(artifactUri) && !zombieArtifact.updatedZombieApp()) {
        // Skips the file because it was already deployed with failure
        return null;
      }
    }

    // check if this artifact is running first, undeployArtifact it then
    T artifact = findArtifact(artifactName);
    boolean isRedeploy = artifact != null;
    try {
      if (isRedeploy) {
        deploymentListener.onRedeploymentStart(artifactName);
        deploymentTemplate.preRedeploy(artifact);
        undeployArtifact(artifactName, false);
      }

      T deployedArtifact = internalDeployPackagedArtifact(artifactUri, deploymentProperties);
      if (isRedeploy) {
        deploymentTemplate.postRedeploy(deployedArtifact);
        deploymentListener.onRedeploymentSuccess(artifactName);
      }
      return deployedArtifact;
    } catch (RuntimeException e) {
      if (isRedeploy) {
        deploymentListener.onRedeploymentFailure(artifactName, e);
      }
      throw e;
    }
  }

  private T internalDeployPackagedArtifact(URI artifactAchivedUri, Optional<Properties> appProperties)
      throws DeploymentException {
    try {
      File artifactLocation = installArtifact(artifactAchivedUri);
      T artifact;
      try {
        artifact = createArtifact(artifactLocation, appProperties);
        trackArtifact(artifact);
      } catch (Throwable t) {
        String artifactName = artifactLocation.getName();

        // error text has been created by the deployer already
        logDeploymentFailure(t, artifactName);

        addZombieFile(artifactName, artifactLocation);

        deploymentListener.onDeploymentFailure(artifactName, t);

        throw t;
      }

      deployArtifact(artifact, appProperties);

      return artifact;
    } catch (Throwable t) {
      if (t instanceof DeploymentException) {
        // re-throw
        throw ((DeploymentException) t);
      }

      final String msg = "Failed to deploy from URI: " + artifactAchivedUri;
      throw new DeploymentException(createStaticMessage(msg), t);
    }
  }

  @Override
  public T deployExplodedArtifact(String artifactDir, Optional<Properties> deploymentProperties) {
    if (!isUpdatedZombieArtifact(artifactDir)) {
      return null;
    }

    return deployExplodedApp(artifactDir, deploymentProperties);
  }
}
