/*
 * Copyright contributors to the Galasa project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package dev.galasa.eclipse.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.apache.felix.bundlerepository.DataModelHelper;
import org.apache.felix.bundlerepository.impl.DataModelHelperImpl;
import org.apache.felix.bundlerepository.impl.RepositoryImpl;
import org.apache.felix.bundlerepository.impl.ResourceImpl;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IVMRunner;
import org.eclipse.jdt.launching.JavaLaunchDelegate;
import org.eclipse.jdt.launching.VMRunnerConfiguration;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.repository.IRepository;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.osgi.framework.Bundle;

import dev.galasa.eclipse.Activator;
import dev.galasa.eclipse.preferences.PreferenceConstants;

public class Launcher extends JavaLaunchDelegate {

    public static final String TEST_CLASS          = "dev.galasa.eclipse.launcher.Class";
    public static final String TRACE               = "dev.galasa.eclipse.launcher.Trace";
    public static final String WORKSPACE_OVERRIDES = "dev.galasa.eclipse.launcher.WorkspaceOverrides";
    public static final String OVERRIDES           = "dev.galasa.eclipse.launcher.Overrides";
    public static final String GRADLE_NATURE	   = "org.eclipse.buildship.core.gradleprojectnature";
    public static final String MAVEN_NATURE	       = "org.eclipse.m2e.core.maven2Nature";

    private MessageConsole     console;
    private PrintStream        consoleDefault;
    private PrintStream        consoleRed;
    private PrintStream        consoleBlue;

    @Override
    public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
            throws CoreException {
        // *** Activate message console
        Display.getDefault().syncExec(new Runnable() {
            @Override
            public void run() {
                activateMessageConsole();
            }
        });

        // *** Retrieve the preferences
        IPreferenceStore preferenceStore = Activator.getInstance().getPreferenceStore();
        String bootstrapUri = preferenceStore.getString(PreferenceConstants.P_BOOTSTRAP_URI);
        String overrideUri = preferenceStore.getString(PreferenceConstants.P_OVERRIDES_URI);
        String remoteMavenUri = preferenceStore.getString(PreferenceConstants.P_REMOTEMAVEN_URI);
        String requestorId = preferenceStore.getString(PreferenceConstants.P_REQUESTOR_ID);
        String obrVersion = preferenceStore.getString(PreferenceConstants.P_OBRVERSION);

        if (bootstrapUri.isEmpty()) {
            bootstrapUri = preferenceStore.getDefaultString(PreferenceConstants.P_BOOTSTRAP_URI);
        }
        if (requestorId.isEmpty()) {
            requestorId = preferenceStore.getDefaultString(PreferenceConstants.P_REQUESTOR_ID);
        }
        obrVersion = obrVersion.trim();
        if (obrVersion.isEmpty() || obrVersion.equalsIgnoreCase("LATEST")) {
            obrVersion = "LATEST";
        }

        // *** Get the project, classname and bundleName
        String project = configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "");
        String testclass = configuration.getAttribute(Launcher.TEST_CLASS, "");
    	Manifest manifest = findManifest(project);
    	String bundleName = findBundleName(manifest);
        if (bundleName == null) {
            return;
        }

        consoleDefault.append("\nLaunching Galasa test " + bundleName + "/" + testclass + "\n");


        // *** Get other configuration properties
        boolean trace = configuration.getAttribute(Launcher.TRACE, false);
        boolean workspaceOverrides = configuration.getAttribute(Launcher.WORKSPACE_OVERRIDES, true);
        String overrides = configuration.getAttribute(Launcher.OVERRIDES, "").trim();

        // *** Find all the information necessary to run
        File bootJarFile = findBootJar();
        consoleDefault.append("Galasa boot jar is located at " + bootJarFile.toURI().toString() + "\n");
        File mavenRepository = locateMavenRepository(project);
        consoleDefault.append("Maven local repository is at " + mavenRepository.toString() + "\n");
        if (remoteMavenUri.isEmpty()) {
            consoleDefault.append("Maven remote repository has not been provided\n");
        } else {
            consoleDefault.append("Maven remote repository is at " + remoteMavenUri + "\n");
        }

        // *** Build the Workspace OBR
        File workspaceOBR = buildWorkspaceOBR();
        if (workspaceOBR == null) {
            return;
        }

        // *** Report the requestor ID
        consoleDefault.append("Requestor is " + requestorId + "\n");

        // *** Report the Bootstrap URI
        consoleDefault.append("Bootstrap URI is " + bootstrapUri + "\n");

        // *** Retrieve all the Launcher Override extensions
        IExtensionRegistry extensionRegistry = Platform.getExtensionRegistry();
        IConfigurationElement[] overrideExtensions = extensionRegistry
                .getConfigurationElementsFor("dev.galasa.eclipse.extension.launcher.overrides");

        // *** Calculate which overrides file to use
        java.nio.file.Path overridesFile = null;
        try {
            overridesFile = Files.createTempFile(Activator.getCachePath(), "galasaoverrides", ".properties");

            Properties generatedOverrides = new Properties();

            if (workspaceOverrides && !overrideUri.isEmpty()) {
                URI uOverrideUri = new URI(overrideUri);
                java.nio.file.Path workspaceOverridesFile = Paths.get(uOverrideUri);
                if (Files.exists(workspaceOverridesFile)) {
                    Properties p = new Properties();
                    p.load(Files.newInputStream(workspaceOverridesFile));

                    generatedOverrides.putAll(p);
                    consoleDefault.append("Loaded overrides from " + workspaceOverridesFile.toString() + "\n");
                }
            }

            if (!overrides.isEmpty()) {
                java.nio.file.Path configOverridesFile = Paths.get(overrides);
                if (Files.exists(configOverridesFile)) {
                    Properties p = new Properties();
                    p.load(Files.newInputStream(configOverridesFile));

                    generatedOverrides.putAll(p);
                    consoleDefault.append("Loaded overrides from " + configOverridesFile.toString() + "\n");
                }
            }

            // *** Ask the extensions to contribute to the overrides
            if (overrideExtensions != null) {
                for (IConfigurationElement extension : overrideExtensions) {
                    try {
                        ILauncherOverridesExtension extensionClass = (ILauncherOverridesExtension) extension
                                .createExecutableExtension("class");
                        extensionClass.appendOverrides(configuration, generatedOverrides);
                    } catch (Exception e1) {
                        Activator.log(e1);
                    }
                }
            }

            // *** Add the framework overrides
            generatedOverrides.put("framework.run.requestor", requestorId);

            // Add the bootstrap url to the overrides for the benefit of the managers
            generatedOverrides.put("framework.bootstrap.url", bootstrapUri);

            // Add the ability to create png files as well as the json
            generatedOverrides.put("zos3270.terminal.output","json,png");

            generatedOverrides.store(Files.newOutputStream(overridesFile), "Galasa overrides file");
        } catch (Exception e) {
            throw new CoreException(
                    new Status(Status.ERROR, Activator.PLUGIN_ID, "Unable to generate overrides file", e));
        }

        // *** Setup the Java running environment
        IVMRunner runner = getVMRunner(configuration, mode);

        // *** Only need the boot.jar on the classpath
        String[] classpath = new String[1];
        classpath[0] = bootJarFile.toString();

        // *** From the config get any environment properties
        String[] envp = getEnvironment(configuration);

        // *** Setup our program arguments
        ArrayList<String> programArguments = new ArrayList<String>();
        programArguments.add("--bootstrap");
        programArguments.add(bootstrapUri);
        if (overridesFile != null) {
            programArguments.add("--overrides");
            programArguments.add(overridesFile.toUri().toString());
        }
        programArguments.add("--localmaven");
        programArguments.add(mavenRepository.toURI().toString());
        if (!remoteMavenUri.isEmpty()) {
            programArguments.add("--remotemaven");
            programArguments.add(remoteMavenUri);
        }
        programArguments.add("--obr");
        programArguments.add(workspaceOBR.toURI().toString());
        programArguments.add("--obr");
        programArguments.add("mvn:dev.galasa/dev.galasa.uber.obr/" + obrVersion + "/obr");
        if (trace) {
            programArguments.add("--trace");
        }
        programArguments.add("--test");
        programArguments.add(bundleName + "/" + testclass);

        // *** Get the vm args from the config
        ArrayList<String> vmArguments = new ArrayList<String>();
        String userVMArgs = getVMArguments(configuration);
        if (userVMArgs != null && !userVMArgs.isEmpty()) {
            String args[] = userVMArgs.split(" ");
            for (String s : args) {
                vmArguments.add(s.trim());
            }
        }
        // VM-specific attributes
        Map<String, Object> vmAttributesMap = getVMSpecificAttributesMap(configuration);

        // *** As can only use a classpath, need to provide main class
        String mainTypeName = "dev.galasa.boot.Launcher";

        VMRunnerConfiguration runConfig = new VMRunnerConfiguration(mainTypeName, classpath);
        runConfig.setVMArguments((String[]) vmArguments.toArray(new String[vmArguments.size()]));
        runConfig.setProgramArguments((String[]) programArguments.toArray(new String[programArguments.size()]));
        runConfig.setEnvironment(envp);
        // runConfig.setWorkingDirectory(workingDirName);
        runConfig.setVMSpecificAttributesMap(vmAttributesMap);

        setDefaultSourceLocator(launch, configuration);

        runner.run(runConfig, launch, monitor);
    }

    private File buildWorkspaceOBR() {
        Instant start = Instant.now();
        consoleDefault.append("\nBuilding workspace OBR, will only include Java Projects\n");

        boolean foundProjects = false;
        HashMap<String, String> rejectedBundles = new HashMap<>();

        DataModelHelper obrDataModelHelper = new DataModelHelperImpl();
        RepositoryImpl newRepository = new RepositoryImpl();

        IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
        IProject[] workspaceProjects = workspaceRoot.getProjects();
        for (IProject workspaceProject : workspaceProjects) {
        	
            try {
                if (!workspaceProject.hasNature(JavaCore.NATURE_ID)) {
                    continue;
                }
            	Manifest manifest = findManifest(workspaceProject.getName());
            	if (manifest == null) {
            		rejectedBundles.put(workspaceProject.getName(), "No manifest");
            		continue;
                }
            	
            	String bundleName = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
                if (bundleName == null || bundleName.trim().isEmpty()) {
                	rejectedBundles.put(workspaceProject.getName(), "Not and OSGi bundle");
            		continue;
                }
                ResourceImpl newResource = (ResourceImpl) obrDataModelHelper
                        .createResource(manifest.getMainAttributes());
                
                if (workspaceProject.hasNature(GRADLE_NATURE)) {
                	// Forcing gradle users to keep output in the build dir
                    IPath outputLocation = getLocation(workspaceProject).append("build");
                    
                    
                    File fileOutputLocation = outputLocation.toFile();
                    
                    if (!fileOutputLocation.exists()) {
                        rejectedBundles.put(workspaceProject.getName(), "Gradle project does not have build directory, path should be " + fileOutputLocation);
                        continue;
                    }
                    File fileLibsLocation = new File(fileOutputLocation, "libs");
                    if (!fileLibsLocation.exists()) {
                        rejectedBundles.put(workspaceProject.getName(), "Gradle project build directory does not have libs directory, path should be " + fileLibsLocation);
                        continue;
                    }
                    File[] outputFiles = fileLibsLocation.listFiles();
                    
                    boolean foundJar = false;
                    for (File file : outputFiles) {
                    	if (file.getName().endsWith(".jar")) {
                    		newResource.put(ResourceImpl.URI, "reference:" + new File(file.getAbsolutePath()).toURI().toString());
                    		foundJar = true;
                    		break;
                    	}
                    }
                    if (!foundJar) {
                        rejectedBundles.put(workspaceProject.getName(), "Gradle project build libs directory does not have a jar");
                        continue;
                    }
                } else if (workspaceProject.hasNature(MAVEN_NATURE)) {
                    IMavenProjectFacade mavenProjectFacade = MavenPlugin.getMavenProjectRegistry().getProject(workspaceProject);
                    String version = mavenProjectFacade.getArtifactKey().version();

                    IPath outputPath = mavenProjectFacade.getOutputLocation().removeLastSegments(1);
                    IResource actualOutputPath = workspaceRoot.findMember(outputPath);
                    java.nio.file.Path realOutputPath = Paths.get(actualOutputPath.getLocationURI());
                    
                    String artifactId = mavenProjectFacade.getArtifactKey().artifactId();
                    if (artifactId == null) {
                        rejectedBundles.put(workspaceProject.getName(), "Artifact ID is missing from project");
                        continue;
                    }
                    
                    File jar = realOutputPath.resolve(artifactId + "-" + version + ".jar").toFile();
                    if (!jar.exists()) {
                        rejectedBundles.put(workspaceProject.getName(), "Jar " + jar.getName() + " is missing from project");
                        continue;
                    }

                    newResource.put(ResourceImpl.URI, "reference:" + new File(jar.getAbsolutePath()).toURI().toString());
                } else {
                    rejectedBundles.put(workspaceProject.getName(), "Unrecognised project nature");
                    continue;
                }
                
                newRepository.addResource(newResource);
                if (workspaceProject.getName().equals(newResource.getSymbolicName())) {
                    consoleBlue.append("Added project/bundle " + newResource.getSymbolicName() + "\n");
                } else {
                    consoleBlue.append("Added project " + workspaceProject.getName() + " with bundle name "
                            + newResource.getSymbolicName() + "\n");
                }
                foundProjects = true;

            } catch (Exception e) {
                rejectedBundles.put(workspaceProject.getName(), "error processing - " + e.getMessage());
                IStatus errorStatus = new Status(IStatus.ERROR, Activator.getPluginId(), IStatus.ERROR, "Workspace OBR error whilst processing workspace project " + workspaceProject.getName(), e);
                Activator.log(errorStatus);
            }

        }

        if (!rejectedBundles.isEmpty()) {
            ArrayList<String> projects = new ArrayList<>(rejectedBundles.keySet());
            Collections.sort(projects);

            consoleDefault.append("\nThe following projects were not included in the workspace OBR:-\n");
            for (String project : projects) {
                consoleDefault.append("   " + project + " rejected because of " + rejectedBundles.get(project) + "\n");
            }
            consoleDefault.append("\n");
        }

        if (!foundProjects) {
            consoleRed.append("No OSGi projects found, terminating early\n");
            return null;
        }

        try {
            java.nio.file.Path stateLocation = Paths.get(Activator.getInstance().getStateLocation().toFile().toURI());
            Files.createDirectories(stateLocation);
            java.nio.file.Path obr = stateLocation.resolve("workspace.obr");

            FileWriter fw = new FileWriter(obr.toFile());
            obrDataModelHelper.writeRepository(newRepository, fw);
            fw.close();

            consoleDefault.append("Workspace OBR is located at " + obr.toAbsolutePath().toString() + "\n");

            Instant end = Instant.now();
            consoleDefault.append(
                    "Build of workspace OBR completed in " + (end.toEpochMilli() - start.toEpochMilli()) + "ms\n\n");

            return obr.toFile();
        } catch (Exception e) {
            consoleRed.append("Write of workspace OBR failed");
            Activator.log(e);
            return null;
        }

    }
    
    private Manifest findManifest(String project) {
    	IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
    	IProject actualProject = workspaceRoot.getProject(project);   
    	IPath projectPath = getLocation(actualProject);
    	
    	try {	    		
    		if (actualProject.hasNature(GRADLE_NATURE)) {
    			consoleDefault.append("This is a gradle project: " + project + "\n");
        		// Enforcing that gradle projects must be build to the build dir for now
        		IPath fullBuildDir = projectPath.append("build");
        		
        		for (File file : fullBuildDir.append("libs").toFile().listFiles()) {
        			if (file.getName().endsWith(".jar")) {
        				consoleDefault.append("Looking for bundle in: " + file.getName() + "\n");
        				Manifest manifest = extractManifestFromJar(new FileInputStream(file));
                        if (manifest.getMainAttributes().getValue("Bundle-SymbolicName") != null) {
                                consoleDefault.append("Found: " + manifest.getMainAttributes().getValue("Bundle-SymbolicName") + "\n");
                                return manifest;
                        }
    		    	}
        		}
        	}
    		
			if (actualProject.hasNature(MAVEN_NATURE)) {
				consoleDefault.append("This is a maven project: " + project + "\n");
				IMavenProjectFacade mavenProjectFacade = MavenPlugin.getMavenProjectRegistry().getProject(actualProject);
				String version = mavenProjectFacade.getArtifactKey().version();
				
				IPath outputPath = mavenProjectFacade.getOutputLocation().removeLastSegments(1);
				IResource actualOutputPath = workspaceRoot.findMember(outputPath);
				java.nio.file.Path realOutputPath = Paths.get(actualOutputPath.getRawLocationURI());
				
				String artifactId = mavenProjectFacade.getArtifactKey().artifactId();
				if (artifactId == null) {
				    consoleDefault.append("Artifact ID is missing from project: " + project + "\n");
				    return null;
				}
				
				File jar = realOutputPath.resolve(artifactId + "-" + version + ".jar").toFile();
				if (!jar.exists()) {
                    consoleDefault.append("Jar " + jar.getName() + " is missing from project: " + project + "\n");
                    return null;
				}
				return extractManifestFromJar(new FileInputStream(jar));
			}
    	} catch (IOException | CoreException e) {
    		consoleRed.append("Failed to open Manifest in project: " + project + "\n");
    	    return null;
    	}
	    
	    consoleRed.append("The test project " + project + " does not have a MANIFEST.MF file\n");
	    return null;
    }
    
    private Manifest extractManifestFromJar(InputStream jar) {
    	JarInputStream jarIn;
		try {
			jarIn = new JarInputStream(jar);
			Manifest mf = jarIn.getManifest();
			jarIn.close();
			return mf;
		} catch (IOException e) {
			consoleRed.append("Could not open jar.");
			return null;
		}
    }

    private String findBundleName(Manifest manifestFile) throws CoreException {
        try {
            String bundleName = manifestFile.getMainAttributes().getValue("Bundle-SymbolicName");
            String[] split = bundleName.split(";");
            return split[0];

        } catch (Exception e) {
            throw new CoreException(new Status(Status.ERROR, Activator.PLUGIN_ID,
                    "Problem locating the bundle name from the project manifest file", e));
        }
    }

    private File locateMavenRepository(String project) {

        IRepository localRepository = MavenPlugin.getRepositoryRegistry().getLocalRepository();
        return localRepository.getBasedir();
    }

    private File findBootJar() throws CoreException {
        try {
            Bundle bundle = Activator.getInstance().getBundle();
            IPath path = new Path("lib/galasa-boot.jar");
            URL bootUrl = FileLocator.find(bundle, path, null);
            if (bootUrl == null) {
                throw new CoreException(new Status(Status.ERROR, Activator.PLUGIN_ID,
                        "The galasa-boot.jar is missing from the plugin"));
            }
            bootUrl = FileLocator.toFileURL(bootUrl);
            return Paths.get(toUri(bootUrl)).toFile().getAbsoluteFile(); 
        } catch (Exception e) {
            throw new CoreException(new Status(Status.ERROR, Activator.PLUGIN_ID,
                    "Problem locating the galasa-boot.jar in the plugin", e));
        }
    }

    /**
     * Activate message console
     */
    private void activateMessageConsole() {
        // Look for existing console
        ConsolePlugin consolePlugin = ConsolePlugin.getDefault();
        IConsoleManager consoleManager = consolePlugin.getConsoleManager();
        IConsole[] existingConsoles = consoleManager.getConsoles();
        for (IConsole existingConsole : existingConsoles) {
            if (existingConsole.getName().equals(Activator.PLUGIN_NAME)) {
                console = (MessageConsole) existingConsole;
                break;
            }
        }

        // Not found, create a new one
        if (console == null) {
            console = new MessageConsole(Activator.PLUGIN_NAME, null);
            consoleManager.addConsoles(new IConsole[] { console });
        }

        // activate console
        console.activate();

        // Create the default PrintStream
        MessageConsoleStream messageConsoleStreamDefault = console.newMessageStream();
        messageConsoleStreamDefault.setColor(null);
        consoleDefault = new PrintStream(messageConsoleStreamDefault, true);

        // Create a PrintStream for Red text
        MessageConsoleStream messageConsoleStreamRed = console.newMessageStream();
        messageConsoleStreamRed.setColor(new Color(null, new RGB(255, 0, 0)));
        consoleRed = new PrintStream(messageConsoleStreamRed, true);

        // Create a PrintStream for Blue text
        MessageConsoleStream messageConsoleStreamBlue = console.newMessageStream();
        messageConsoleStreamBlue.setColor(new Color(null, new RGB(0, 0, 255)));
        consoleBlue = new PrintStream(messageConsoleStreamBlue, true);
    }
    
    public static URI toUri(URL url) throws URISyntaxException {
        String sUrl = url.toString();
        
        sUrl = sUrl.replaceAll(" ", "%20");
        
        return new URI(sUrl);
    }
    
    private IPath getLocation(IProject project) {
    	IPath location = project.getRawLocation();
    	if (location != null) {
    		return location;
    	}
    	
    	return project.getLocation();
    }

}
