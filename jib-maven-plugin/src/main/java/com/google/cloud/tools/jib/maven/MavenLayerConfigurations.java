/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.maven;

import com.google.cloud.tools.jib.filesystem.AbsoluteUnixPath;
import com.google.cloud.tools.jib.filesystem.DirectoryWalker;
import com.google.cloud.tools.jib.frontend.JavaEntrypointConstructor;
import com.google.cloud.tools.jib.frontend.JavaLayerConfigurations;
import com.google.cloud.tools.jib.frontend.JavaLayerConfigurations.Builder;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

/** Builds {@link JavaLayerConfigurations} based on inputs from a {@link MavenProject}. */
class MavenLayerConfigurations {

  /**
   * Resolves the {@link JavaLayerConfigurations} for a {@link MavenProject}.
   *
   * @param project the {@link MavenProject}
   * @param extraDirectory path to the directory for the extra files layer
   * @param appRoot root directory in the image where the app will be placed
   * @return a {@link JavaLayerConfigurations} for the project
   * @throws IOException if collecting the project files fails
   */
  static JavaLayerConfigurations getForProject(
      MavenProject project, Path extraDirectory, AbsoluteUnixPath appRoot) throws IOException {
    if ("war".equals(project.getPackaging())) {
      return getForWarProject(project, extraDirectory, appRoot);
    } else {
      return getForNonWarProject(project, extraDirectory, appRoot);
    }
  }

  /**
   * Resolves the {@link JavaLayerConfigurations} for a non-WAR {@link MavenProject}.
   *
   * @param project the {@link MavenProject}
   * @param extraDirectory path to the directory for the extra files layer
   * @param appRoot root directory in the image where the app will be placed
   * @return a {@link JavaLayerConfigurations} for the project
   * @throws IOException if collecting the project files fails
   */
  private static JavaLayerConfigurations getForNonWarProject(
      MavenProject project, Path extraDirectory, AbsoluteUnixPath appRoot) throws IOException {

    AbsoluteUnixPath dependenciesExtractionPath =
        appRoot.resolve(JavaEntrypointConstructor.DEFAULT_RELATIVE_DEPENDENCIES_PATH_ON_IMAGE);
    AbsoluteUnixPath resourcesExtractionPath =
        appRoot.resolve(JavaEntrypointConstructor.DEFAULT_RELATIVE_RESOURCES_PATH_ON_IMAGE);
    AbsoluteUnixPath classesExtractionPath =
        appRoot.resolve(JavaEntrypointConstructor.DEFAULT_RELATIVE_CLASSES_PATH_ON_IMAGE);

    Builder layerBuilder = JavaLayerConfigurations.builder();

    // Gets all the dependencies.
    for (Artifact artifact : project.getArtifacts()) {
      Path artifactPath = artifact.getFile().toPath();
      if (artifact.isSnapshot()) {
        layerBuilder.addSnapshotDependencyFile(
            artifactPath, dependenciesExtractionPath.resolve(artifactPath.getFileName()));
      } else {
        layerBuilder.addDependencyFile(
            artifactPath, dependenciesExtractionPath.resolve(artifactPath.getFileName()));
      }
    }

    Path classesOutputDirectory = Paths.get(project.getBuild().getOutputDirectory());

    // Gets the classes files in the 'classes' output directory.
    Predicate<Path> isClassFile = path -> path.toString().endsWith(".class");
    addFilesToLayer(
        classesOutputDirectory, isClassFile, classesExtractionPath, layerBuilder::addClassFile);

    // Gets the resources files in the 'classes' output directory.
    addFilesToLayer(
        classesOutputDirectory,
        isClassFile.negate(),
        resourcesExtractionPath,
        layerBuilder::addResourceFile);

    // Adds all the extra files.
    if (Files.exists(extraDirectory)) {
      AbsoluteUnixPath extractionBase = AbsoluteUnixPath.get("/");
      addFilesToLayer(extraDirectory, path -> true, extractionBase, layerBuilder::addExtraFile);
    }

    return layerBuilder.build();
  }

  /**
   * Resolves the {@link JavaLayerConfigurations} for a WAR {@link MavenProject}.
   *
   * @param project the {@link MavenProject}
   * @param extraDirectory path to the directory for the extra files layer
   * @param appRoot root directory in the image where the app will be placed
   * @return a {@link JavaLayerConfigurations} for the project
   * @throws IOException if collecting the project files fails
   */
  private static JavaLayerConfigurations getForWarProject(
      MavenProject project, Path extraDirectory, AbsoluteUnixPath appRoot) throws IOException {

    // TODO explode the WAR file rather than using this directory. The contents of the final WAR may
    // be different from this directory (it's possible to include or exclude files when packaging a
    // WAR). Also the exploded WAR directory is configurable with <webappDirectory> and may not be
    // at build.getFinalName().
    Path explodedWarPath =
        Paths.get(project.getBuild().getDirectory()).resolve(project.getBuild().getFinalName());
    Path webInfClasses = explodedWarPath.resolve("WEB-INF/classes");
    Path webInfLib = explodedWarPath.resolve("WEB-INF/lib");

    AbsoluteUnixPath dependenciesExtractionPath = appRoot.resolve("WEB-INF/lib");
    AbsoluteUnixPath classesExtractionPath = appRoot.resolve("WEB-INF/classes");

    Builder layerBuilder = JavaLayerConfigurations.builder();

    // Gets all the dependencies.
    Predicate<Path> isSnapshotDependency =
        path -> path.toString().contains(JavaLayerConfigurations.SNAPSHOT_FILENAME_SUFFIX);
    if (Files.exists(webInfLib)) {
      addFilesToLayer(
          webInfLib,
          isSnapshotDependency,
          dependenciesExtractionPath,
          layerBuilder::addSnapshotDependencyFile);
      addFilesToLayer(
          webInfLib,
          isSnapshotDependency.negate(),
          dependenciesExtractionPath,
          layerBuilder::addDependencyFile);
    }

    // Gets the classes files in the 'WEB-INF/classes' output directory.
    Predicate<Path> isClassFile = path -> path.getFileName().toString().endsWith(".class");
    if (Files.exists(webInfClasses)) {
      addFilesToLayer(
          webInfClasses, isClassFile, classesExtractionPath, layerBuilder::addClassFile);
    }

    // Gets the resources.
    Predicate<Path> isResource =
        path -> {
          boolean inWebInfClasses = path.startsWith(webInfClasses);
          boolean inWebInfLib = path.startsWith(webInfLib);

          return (!inWebInfClasses && !inWebInfLib) || (inWebInfClasses && !isClassFile.test(path));
        };
    addFilesToLayer(explodedWarPath, isResource, appRoot, layerBuilder::addResourceFile);

    // Adds all the extra files.
    if (Files.exists(extraDirectory)) {
      AbsoluteUnixPath extractionBase = AbsoluteUnixPath.get("/");
      addFilesToLayer(extraDirectory, path -> true, extractionBase, layerBuilder::addExtraFile);
    }

    return layerBuilder.build();
  }

  @FunctionalInterface
  @VisibleForTesting
  static interface FileToLayerAdder {

    void add(Path sourcePath, AbsoluteUnixPath pathInContainer) throws IOException;
  }

  @VisibleForTesting
  static boolean isEmptyDirectory(Path path) throws IOException {
    if (Files.isDirectory(path)) {
      try (Stream<Path> stream = Files.list(path)) {
        return !stream.findAny().isPresent();
      }
    }
    return false;
  }

  /**
   * Adds files to a layer selectively and recursively. {@code sourceRoot} must be a directory.
   * Non-empty directories will be ignored, while empty directories will always be added. Note that
   * empty directories will always be added regardless of {@code pathFilter}.
   *
   * <p>The contents of {@code sourceRoot} will be placed into {@code basePathInContainer}. For
   * example, if {@code sourceRoot} is {@code /usr/home}, {@code /usr/home/passwd} exists locally,
   * and {@code basePathInContainer} is {@code /etc}, then the image will have {@code /etc/passwd}.
   *
   * @param sourceRoot root directory whose contents will be added
   * @param pathFilter only the files satisfying the filter will be added, unless the files are
   *     empty directories
   * @param basePathInContainer directory in the layer into which the source contents are added
   * @param addFileToLayer function that should add the file to the layer; the function gets the
   *     path of the source file (may be a directory) and the final destination path in the layer
   * @throws IOException error while listing directories
   * @throws NotDirectoryException if {@code sourceRoot} is not a directory
   */
  @VisibleForTesting
  static void addFilesToLayer(
      Path sourceRoot,
      Predicate<Path> pathFilter,
      AbsoluteUnixPath basePathInContainer,
      FileToLayerAdder addFileToLayer)
      throws IOException {

    new DirectoryWalker(sourceRoot)
        .walk(
            path -> {
              // Always add empty directories. However, ignore non-empty directories because
              // otherwise JavaLayerConfigurations will add files recursively.
              if (isEmptyDirectory(path) || (!Files.isDirectory(path) && pathFilter.test(path))) {
                addFileToLayer.add(path, basePathInContainer.resolve(sourceRoot.relativize(path)));
              }
            });
  }

  private MavenLayerConfigurations() {}
}
