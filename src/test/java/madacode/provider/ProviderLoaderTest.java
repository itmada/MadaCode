package madacode.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsAndSavesOptionalPricing() throws Exception {
        Path providersFile = tempDir.resolve("providers.json");
        Files.writeString(providersFile, """
                {
                  "providers": [
                    {
                      "name": "provider-a",
                      "authToken": "token",
                      "baseUrl": "https://api.example.com",
                      "defaultModel": "model-a",
                      "supportsPromptCaching": true,
                      "pricing": {
                        "inputUsdPerMillion": 3.0,
                        "outputUsdPerMillion": 15.0
                      },
                      "models": [{ "name": "model-a" }]
                    }
                  ]
                }
                """);

        ProviderLoader loader = new ProviderLoader(providersFile);
        List<Provider> providers = loader.load();

        assertEquals(3.0, providers.getFirst().pricing().inputUsdPerMillion());
        assertEquals(15.0, providers.getFirst().pricing().outputUsdPerMillion());

        loader.save(providers);
        JsonNode root = new ObjectMapper().readTree(providersFile.toFile());

        assertEquals(3.0, root.path("providers").get(0)
                .path("pricing").path("inputUsdPerMillion").asDouble());
        assertEquals(15.0, root.path("providers").get(0)
                .path("pricing").path("outputUsdPerMillion").asDouble());
    }

    @Test
    void resolvesAuthTokenFromEnvironmentReferenceWithoutPersistingReference() throws Exception {
        Path providersFile = tempDir.resolve("providers.json");
        System.setProperty("MADA_TEST_PROVIDER_TOKEN", "env-token");
        try {
            Files.writeString(providersFile, """
                    {
                      "providers": [
                        {
                          "name": "provider-a",
                          "authTokenEnv": "MADA_TEST_PROVIDER_TOKEN",
                          "baseUrl": "https://api.example.com",
                          "defaultModel": "model-a",
                          "models": [{ "name": "model-a" }]
                        }
                      ]
                    }
                    """);

            ProviderLoader loader = new ProviderLoader(providersFile);
            List<Provider> providers = loader.load();

            assertEquals("env-token", providers.getFirst().authToken());
        } finally {
            System.clearProperty("MADA_TEST_PROVIDER_TOKEN");
        }
    }

    @Test
    void failsClosedWhenAuthTokenEnvIsUnset() throws Exception {
        Path providersFile = tempDir.resolve("providers.json");
        Files.writeString(providersFile, """
                {
                  "providers": [
                    {
                      "name": "provider-a",
                      "authTokenEnv": "MADA_TEST_PROVIDER_TOKEN_MISSING",
                      "baseUrl": "https://api.example.com",
                      "defaultModel": "model-a",
                      "models": [{ "name": "model-a" }]
                    }
                  ]
                }
                """);

        ProviderException error = assertThrows(
                ProviderException.class,
                () -> new ProviderLoader(providersFile).load());

        assertEquals("Environment variable 'MADA_TEST_PROVIDER_TOKEN_MISSING' declared by authTokenEnv "
                + "in provider 'provider-a' is not set", error.getMessage());
    }
}
