/*
 * Copyright 2000-2022 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.flow.server.frontend;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.slf4j.LoggerFactory;

import com.vaadin.experimental.FeatureFlags;
import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.server.ExecutionFailedException;
import com.vaadin.flow.server.PwaConfiguration;
import com.vaadin.flow.server.frontend.scanner.ClassFinder;
import com.vaadin.flow.server.frontend.scanner.FrontendDependenciesScanner;

/**
 * An executor that it's run when the servlet context is initialised in dev-mode
 * or when flow-maven-plugin goals are run. It can chain a set of task to run.
 * <p>
 * For internal use only. May be renamed or removed in a future release.
 *
 * @since 2.0
 */
public class NodeTasks implements FallibleCommand {

    // @formatter:off
    // This list keeps the tasks in order so that they are executed
    // without depending on when they are added.
    private static final List<Class<? extends FallibleCommand>> commandOrder =
        Collections.unmodifiableList(Arrays.asList(
            TaskGeneratePackageJson.class,
            TaskGenerateIndexHtml.class,
            TaskGenerateIndexTs.class,
            TaskGenerateViteDevMode.class,
            TaskGenerateTsConfig.class,
            TaskGenerateTsDefinitions.class,
            TaskGenerateServiceWorker.class,
            TaskGenerateOpenAPI.class,
            TaskGenerateEndpoint.class,
            TaskGenerateBootstrap.class,
            TaskGenerateWebComponentHtml.class,
            TaskGenerateWebComponentBootstrap.class,
            TaskGenerateFeatureFlags.class,
            TaskInstallFrontendBuildPlugins.class,
            TaskUpdatePackages.class,
            TaskRunNpmInstall.class,
            TaskGenerateHilla.class,
            TaskCopyFrontendFiles.class,
            TaskCopyLocalFrontendFiles.class,
            TaskUpdateSettingsFile.class,
            TaskUpdateVite.class,
            TaskUpdateImports.class,
            TaskUpdateThemeImport.class,
            TaskCopyTemplateFiles.class,
            TaskRunDevBundleBuild.class
        ));
    // @formatter:on

    private final List<FallibleCommand> commands = new ArrayList<>();

    /**
     * Initialize tasks with the given options.
     *
     * @param options
     *            the options
     */
    public NodeTasks(Options options) {

        ClassFinder classFinder = new ClassFinder.CachedClassFinder(
                options.classFinder);
        FrontendDependenciesScanner frontendDependencies = null;

        final FeatureFlags featureFlags = options.getFeatureFlags();

        if (options.enablePackagesUpdate || options.enableImportsUpdate
                || options.enableWebpackConfigUpdate) {
            frontendDependencies = new FrontendDependenciesScanner.FrontendDependenciesScannerFactory()
                    .createScanner(!options.useByteCodeScanner, classFinder,
                            options.generateEmbeddableWebComponents,
                            featureFlags);

            // The dev bundle check needs the frontendDependencies to be able to
            // determine if we need a rebuild as the check happens immediately
            // and no update tasks are executed before it.
            if (!options.productionMode && options.isDevBundleBuild()) {
                if (TaskRunDevBundleBuild.needsBuild(options,
                        frontendDependencies, classFinder)) {
                    options.runNpmInstall(true);
                    options.copyTemplates(true);
                } else {
                    // A dev bundle build is not needed after all, skip it
                    options.withDevBundleBuild(false);
                }
            }

            if (options.generateEmbeddableWebComponents) {
                FrontendWebComponentGenerator generator = new FrontendWebComponentGenerator(
                        classFinder);
                Set<File> webComponents = generator.generateWebComponents(
                        options.getGeneratedFolder(),
                        frontendDependencies.getThemeDefinition());

                if (webComponents.size() > 0) {
                    commands.add(new TaskGenerateWebComponentHtml(options));
                    commands.add(
                            new TaskGenerateWebComponentBootstrap(options));
                }
            }

            TaskUpdatePackages packageUpdater = null;
            if (options.enablePackagesUpdate
                    && options.jarFrontendResourcesFolder != null) {
                packageUpdater = new TaskUpdatePackages(classFinder,
                        frontendDependencies, options);
                commands.add(packageUpdater);
            }

            if (packageUpdater != null && options.runNpmInstall) {
                commands.add(new TaskRunNpmInstall(packageUpdater, options));

                commands.add(new TaskInstallFrontendBuildPlugins(options));
            }

            if (packageUpdater != null && options.isDevBundleBuild()) {
                commands.add(new TaskRunDevBundleBuild(options));
            }

        }

        if (options.createMissingPackageJson) {
            TaskGeneratePackageJson packageCreator = new TaskGeneratePackageJson(
                    options);
            commands.add(packageCreator);
        }

        if (frontendDependencies != null) {
            addGenerateServiceWorkerTask(options,
                    frontendDependencies.getPwaConfiguration());

            if (options.productionMode || options.isEnableDevServer()
                    || options.isDevBundleBuild()) {
                addGenerateTsConfigTask(options);
            }
        }

        addBootstrapTasks(options);

        // use the new Hilla generator if enabled, otherwise use the old
        // generator.
        TaskGenerateHilla hillaTask;
        if (options.endpointGeneratedOpenAPIFile != null
                && featureFlags.isEnabled(FeatureFlags.HILLA_ENGINE)
                && (hillaTask = options.lookup
                        .lookup(TaskGenerateHilla.class)) != null) {
            hillaTask.configure(options.getNpmFolder(),
                    options.getBuildDirectoryName());
            commands.add(hillaTask);
        } else if (options.endpointGeneratedOpenAPIFile != null
                && options.endpointSourceFolder != null
                && options.endpointSourceFolder.exists()) {
            addEndpointServicesTasks(options);
        }

        commands.add(new TaskGenerateBootstrap(frontendDependencies, options));

        commands.add(new TaskGenerateFeatureFlags(options));

        if (options.jarFiles != null
                && options.jarFrontendResourcesFolder != null) {
            commands.add(new TaskCopyFrontendFiles(options));
        }

        if (options.localResourcesFolder != null
                && options.jarFrontendResourcesFolder != null) {
            commands.add(new TaskCopyLocalFrontendFiles(options));
        }

        String themeName = "";
        PwaConfiguration pwa;
        if (frontendDependencies != null) {
            if (frontendDependencies.getThemeDefinition() != null) {
                themeName = frontendDependencies.getThemeDefinition().getName();
            }
            pwa = frontendDependencies.getPwaConfiguration();
        } else {
            pwa = new PwaConfiguration();
        }
        commands.add(new TaskUpdateSettingsFile(options, themeName, pwa));
        if (options.productionMode || options.isEnableDevServer()
                || options.isDevBundleBuild()) {
            commands.add(new TaskUpdateVite(options));
        }

        if (options.enableImportsUpdate) {
            commands.add(new TaskUpdateImports(classFinder,
                    frontendDependencies,
                    finder -> getFallbackScanner(options, finder, featureFlags),
                    options));

            commands.add(new TaskUpdateThemeImport(
                    frontendDependencies.getThemeDefinition(), options));
        }

        if (options.copyTemplates) {
            commands.add(new TaskCopyTemplateFiles(classFinder, options));

        }
    }

    private void addBootstrapTasks(Options options) {
        commands.add(new TaskGenerateIndexHtml(options));
        if (options.productionMode || options.isEnableDevServer()
                || options.isDevBundleBuild()) {
            commands.add(new TaskGenerateIndexTs(options));
            if (!options.productionMode) {
                commands.add(new TaskGenerateViteDevMode(options));
            }
        }
    }

    private void addGenerateTsConfigTask(Options options) {
        TaskGenerateTsConfig taskGenerateTsConfig = new TaskGenerateTsConfig(
                options);
        commands.add(taskGenerateTsConfig);

        TaskGenerateTsDefinitions taskGenerateTsDefinitions = new TaskGenerateTsDefinitions(
                options);
        commands.add(taskGenerateTsDefinitions);

    }

    private void addGenerateServiceWorkerTask(Options options,
            PwaConfiguration pwaConfiguration) {
        if (pwaConfiguration.isEnabled()) {
            commands.add(new TaskGenerateServiceWorker(options));
        }
    }

    private void addEndpointServicesTasks(Options options) {
        Lookup lookup = options.lookup;
        EndpointGeneratorTaskFactory endpointGeneratorTaskFactory = lookup
                .lookup(EndpointGeneratorTaskFactory.class);

        if (endpointGeneratorTaskFactory != null) {
            TaskGenerateOpenAPI taskGenerateOpenAPI = endpointGeneratorTaskFactory
                    .createTaskGenerateOpenAPI(options);
            commands.add(taskGenerateOpenAPI);

            if (options.frontendGeneratedFolder != null) {
                TaskGenerateEndpoint taskGenerateEndpoint = endpointGeneratorTaskFactory
                        .createTaskGenerateEndpoint(options);
                commands.add(taskGenerateEndpoint);
            }
        }
    }

    private FrontendDependenciesScanner getFallbackScanner(Options options,
            ClassFinder finder, FeatureFlags featureFlags) {
        if (options.useByteCodeScanner) {
            return new FrontendDependenciesScanner.FrontendDependenciesScannerFactory()
                    .createScanner(true, finder,
                            options.generateEmbeddableWebComponents,
                            featureFlags, true);
        } else {
            return null;
        }
    }

    @Override
    public void execute() throws ExecutionFailedException {
        sortCommands(commands);

        for (FallibleCommand command : commands) {
            command.execute();
        }
    }

    /**
     * Sort command list so we always execute commands in a pre-defined order.
     *
     * @param commandList
     *            list of FallibleCommands to sort
     */
    private void sortCommands(List<FallibleCommand> commandList) {
        commandList.sort((c1, c2) -> {
            final int indexOf1 = getIndex(c1);
            final int indexOf2 = getIndex(c2);
            if (indexOf1 == -1 || indexOf2 == -1) {
                return 0;
            }
            return indexOf1 - indexOf2;
        });
    }

    /**
     * Find index of command for which it is assignable to.
     *
     * @param command
     *            command to find execution index for
     * @return index of command or -1 if not available
     */
    private int getIndex(FallibleCommand command) {
        int index = commandOrder.indexOf(command.getClass());
        if (index != -1) {
            return index;
        }
        for (int i = 0; i < commandOrder.size(); i++) {
            if (commandOrder.get(i).isAssignableFrom(command.getClass())) {
                return i;
            }
        }
        throw new UnknownTaskException(command);
    }
}
