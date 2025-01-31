package com.github.egoettelmann.spring.configuration.extensions.aggregator.maven.components.aggregation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.egoettelmann.spring.configuration.extensions.aggregator.maven.core.AggregationService;
import com.github.egoettelmann.spring.configuration.extensions.aggregator.maven.core.RepositoryService;
import com.github.egoettelmann.spring.configuration.extensions.aggregator.maven.core.dto.PropertiesFile;
import com.github.egoettelmann.spring.configuration.extensions.aggregator.maven.core.exceptions.MetadataFileNotFoundException;
import com.github.egoettelmann.spring.configuration.extensions.aggregator.maven.core.exceptions.OperationFailedException;
import com.github.egoettelmann.spring.configuration.extensions.aggregator.maven.core.model.AggregatedPropertyMetadata;
import com.github.egoettelmann.spring.configuration.extensions.aggregator.maven.core.model.PropertyMetadata;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.util.*;

public class DefaultAggregationService implements AggregationService {

    private static final List<String> METADATA_FILE_SET = Arrays.asList(
            "/META-INF/spring-configuration-metadata.json",
            "/META-INF/additional-spring-configuration-metadata.json"
    );

    private static final String AGGREGATED_FILE = "/META-INF/aggregated-spring-configuration-metadata.json";

    public final static String DEFAULT_PROFILE = "default";

    private final Log log;

    private final RepositoryService repositoryService;

    private final MavenProject project;

    private final PropertiesMetadataReader propertiesMetadataReader;

    private final AggregatedPropertiesMetadataReader aggregatedPropertiesMetadataReader;

    private final AggregatedPropertiesMetadataWriter aggregatedPropertiesMetadataWriter;

    private final PropertiesValueReader propertiesValueReader;

    public DefaultAggregationService(
            final Log log,
            final MavenProject project,
            final RepositoryService repositoryService,
            final ObjectMapper objectMapper
    ) {
        this.log = log;
        this.repositoryService = repositoryService;
        this.project = project;
        this.propertiesMetadataReader = new PropertiesMetadataReader(this.log, objectMapper);
        this.aggregatedPropertiesMetadataReader = new AggregatedPropertiesMetadataReader(this.log, objectMapper);
        this.aggregatedPropertiesMetadataWriter = new AggregatedPropertiesMetadataWriter(this.log, objectMapper);
        this.propertiesValueReader = new PropertiesValueReader(this.log);
    }

    @Override
    public List<AggregatedPropertyMetadata> aggregate(final List<PropertiesFile> propertiesFiles, final Set<String> profiles) {
        final AggregationBuilder builder = new AggregationBuilder(this.log);

        // Resolving from current project
        this.log.debug("Retrieving configuration properties metadata from current project");
        final String projectPath = "file:///" + this.project.getBuild().getOutputDirectory();
        final List<PropertyMetadata> projectProperties = this.readPropertiesFromPath(projectPath);
        builder.add(projectProperties, this.project.getGroupId(), this.project.getArtifactId());

        // Resolving from dependencies
        final List<Artifact> dependencies = this.repositoryService.resolveDependencies(this.project);
        this.log.debug("Retrieving configuration properties metadata from " + dependencies.size() + " dependencies");
        for (final Artifact dependency : dependencies) {
            final String filePath = "jar:file:" + dependency.getFile().getAbsolutePath() + "!";
            final List<PropertyMetadata> properties = this.readPropertiesFromPath(filePath);
            builder.add(properties, dependency.getGroupId(), dependency.getArtifactId());
        }

        // Adding default values
        if (propertiesFiles != null) {
            for (final PropertiesFile propertiesFile : propertiesFiles) {
                final Map<String, Properties> properties = this.loadPropertiesValues(propertiesFile.getPath());
                for (final Map.Entry<String, Properties> entry : properties.entrySet()) {
                    if (profiles == null || profiles.contains(entry.getKey())) {
                        builder.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        // Aggregating
        final List<AggregatedPropertyMetadata> aggregate = builder.build();
        this.log.debug("Resolved metadata for " + aggregate.size() + " configuration properties");
        return aggregate;
    }

    @Override
    public List<AggregatedPropertyMetadata> load() throws MetadataFileNotFoundException {
        final String aggregatedFilePath = "file:///" + this.project.getBuild().getOutputDirectory() + AGGREGATED_FILE;
        return this.aggregatedPropertiesMetadataReader.read(aggregatedFilePath);
    }

    @Override
    public List<AggregatedPropertyMetadata> load(final Artifact artifact) throws MetadataFileNotFoundException {
        final String aggregatedFilePath = "jar:file:" + artifact.getFile().getAbsolutePath() + "!" + AGGREGATED_FILE;
        return this.aggregatedPropertiesMetadataReader.read(aggregatedFilePath);
    }

    @Override
    public void save(final List<AggregatedPropertyMetadata> aggregatedProperties) throws OperationFailedException {
        final String aggregatedFilePath = "file:///" + this.project.getBuild().getOutputDirectory() + AGGREGATED_FILE;

        try {
            // Writing to file
            final URL url = new URL(aggregatedFilePath);
            this.aggregatedPropertiesMetadataWriter.write(aggregatedProperties, url);
        } catch (IOException e) {
            throw new OperationFailedException("Failed to write to file " + aggregatedFilePath, e);
        }
    }

    private List<PropertyMetadata> readPropertiesFromPath(final String path) {
        final List<PropertyMetadata> properties = new ArrayList<>();

        // Checking each file
        for (final String metadataFile : METADATA_FILE_SET) {
            final String filePath = path + metadataFile;
            try {
                // Parsing file
                final List<PropertyMetadata> metadata = this.propertiesMetadataReader.read(filePath);
                this.log.debug("Found metadata for " + metadata.size() + " configuration properties in " + filePath);
                properties.addAll(metadata);
            } catch (final MetadataFileNotFoundException e) {
                this.log.debug("No metadata for configuration properties found in " + filePath);
            }
        }

        this.log.debug("Found metadata for " + properties.size() + " configuration properties in " + path);
        return properties;
    }

    private Map<String, Properties> loadPropertiesValues(final String path) {
        final Map<String, Properties> properties = new HashMap<>();
        properties.put(DEFAULT_PROFILE, new Properties());

        final File propertiesFile = new File(path);
        try {
            final List<Properties> propertiesList = this.propertiesValueReader.read("file:///" + path);
            for (final Properties values : propertiesList) {
                final String profiles = (String) values.getOrDefault("spring.profiles", values.get("spring.config.activate.on-profile"));
                if (StringUtils.isBlank(profiles)) {
                    properties.get(DEFAULT_PROFILE).putAll(values);
                    continue;
                }
                for (final String profile : profiles.split(",")) {
                    properties.putIfAbsent(profile.trim(), new Properties());
                    properties.get(profile.trim()).putAll(values);
                }
            }
        } catch (final MetadataFileNotFoundException e) {
            this.log.warn("No properties found in " + path);
            this.log.debug(e);
        }

        final File folder = propertiesFile.getParentFile();
        final String baseName = FilenameUtils.getBaseName(propertiesFile.getName());
        final String extension = FilenameUtils.getExtension(propertiesFile.getName());
        final FileFilter fileFilter = new WildcardFileFilter(baseName + "-*." + extension);
        this.log.debug("Looking up " + folder.getPath() + " for '" + baseName + "-*." + extension + "'");
        final File[] files = folder.listFiles(fileFilter);
        if (files == null) {
            return properties;
        }
        this.log.debug("Found " + files.length + " profile specific files to parse");

        // Reading profile specific files
        for (final File file : files) {
            try {
                final String profile = FilenameUtils.getBaseName(file.getName()).replace(baseName + "-", "").trim();
                this.log.debug("Parsing file " + file.getPath() + " for profile " + profile);
                final List<Properties> profilePropertiesList = this.propertiesValueReader.read("file:///" + file.getPath());
                for (final Properties values : profilePropertiesList) {
                    final String subProfiles = (String) values.getOrDefault("spring.profiles", values.get("spring.config.activate.on-profile"));
                    if (StringUtils.isBlank(subProfiles)) {
                        properties.putIfAbsent(profile, new Properties());
                        properties.get(profile).putAll(values);
                        continue;
                    }
                    for (final String subProfile : subProfiles.split(",")) {
                        final String combinedProfile = profile + " & " + subProfile.trim();
                        properties.putIfAbsent(combinedProfile, new Properties());
                        properties.get(combinedProfile).putAll(values);
                    }
                }
            } catch (final Exception e) {
                this.log.warn("Error reading profile specific file " + file.getPath());
                this.log.debug(e);
            }
        }

        return properties;
    }

}
