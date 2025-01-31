package com.github.egoettelmann.spring.configuration.extensions.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.diff.JsonDiff;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.InputStream;
import java.util.Properties;

public class ReportGenerationIT {

    @Test
    public void testReportGeneration() throws Exception {
        final String version = this.getProjectVersion();
        Assertions.assertNotNull(version, "Version should not be null");

        final File generatedFile = ResourceUtils.getFile("target/spring-configuration-extensions-samples-" + version + "-configurations.json");
        try (final InputStream expectedIS = new ClassPathResource("report-test-1.json").getInputStream()) {
            Assertions.assertTrue(generatedFile.exists(), "Report file should exist");
            final JsonNode generatedJsonNode = new ObjectMapper().readTree(generatedFile);
            Assertions.assertTrue(generatedJsonNode.has("artifacts"), "No field 'artifacts' found");
            final JsonNode generatedArtifacts = generatedJsonNode.get("artifacts");

            final JsonNode expectedJsonNode = new ObjectMapper().readTree(expectedIS);
            final JsonNode expectedArtifacts = expectedJsonNode.get("artifacts");

            Assertions.assertEquals(expectedArtifacts.size(), generatedArtifacts.size(), "Array of 'artifacts' should be of same size");

            for (int i = 0; i < generatedArtifacts.size(); i++) {
                final JsonNode generatedArtifact = generatedArtifacts.get(i);
                Assertions.assertTrue(generatedArtifact.has("properties"), "No field 'properties' found");
                final JsonNode generatedProperties = generatedArtifact.get("properties");

                final JsonNode expectedArtifact = expectedArtifacts.get(i);
                final JsonNode expectedProperties = expectedArtifact.get("properties");

                Assertions.assertEquals(expectedArtifact.get("groupId"), generatedArtifact.get("groupId"), "Wrong 'groupId'");
                Assertions.assertEquals(expectedArtifact.get("artifactId"), generatedArtifact.get("artifactId"), "Wrong 'artifactId'");
                Assertions.assertEquals(expectedArtifact.get("name"), generatedArtifact.get("name"), "Wrong 'name'");
                Assertions.assertEquals(expectedArtifact.get("description"), generatedArtifact.get("description"), "Wrong 'description'");

                final JsonNode jsonPatch = JsonDiff.asJson(expectedProperties, generatedProperties);
                Assertions.assertTrue(jsonPatch.isArray(), "Patch should be an array");
                Assertions.assertEquals(0, jsonPatch.size(), "Patch should be empty");
            }

        }
    }

    private String getProjectVersion() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/META-INF/maven/com.github.egoettelmann/spring-configuration-extensions-samples/pom.properties")) {
            final Properties p = new Properties();
            p.load(is);
            return p.getProperty("version", null);
        }
    }

}
