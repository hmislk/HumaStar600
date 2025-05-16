package org.carecode.lims.mw;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.ResultsRecord;
import org.carecode.lims.libraries.PatientRecord;
import org.carecode.lims.libraries.MiddlewareSettings;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.carecode.lims.libraries.DataBundle;

public class LISCommunicator {

    private final Logger logger;
    private static final Gson gson = new Gson();
    private final MiddlewareSettings middlewareSettings;

    public LISCommunicator(Logger logger, MiddlewareSettings settings) {
        this.logger = logger;
        this.middlewareSettings = settings;
    }

    public Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx != -1) {
                params.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return params;
    }

    public Map<String, String> parseIonData(Map<String, String> params, String ion) {
        Map<String, String> ionData = new HashMap<>();
        String prefix = "ionData[" + ion + "]";
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                try {
                    String keySegment = entry.getKey().substring(entry.getKey().indexOf('['), entry.getKey().lastIndexOf(']') + 1);
                    String subKey = keySegment.replaceAll("\\[|\\]", "").replace(ion, "").replaceAll("^\\.", "");
                    if (!subKey.isEmpty()) {
                        ionData.put(subKey, entry.getValue());
                    }
                } catch (StringIndexOutOfBoundsException e) {
                    logger.error("Error parsing ion data for key: " + entry.getKey(), e);
                }
            }
        }
        return ionData;
    }

    // Method to extract ion-specific data
    public Map<String, String> parseIonData(Map<String, String> params, String ion, boolean otherMethod) {
        Map<String, String> ionData = new HashMap<>();
        String prefix = "ionData[" + ion + "]";
        params.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                try {
                    String subKey = key.substring(key.indexOf('['), key.lastIndexOf(']') + 1);
                    subKey = subKey.replaceAll("\\[|\\]", "").replace(ion, "").replaceAll("^\\.", ""); // Remove leading dots if any
                    if (!subKey.isEmpty()) {
                        ionData.put(subKey, value);
                    }
                } catch (StringIndexOutOfBoundsException e) {
                    logger.error("Error parsing ion data for key: " + key, e);
                }
            }
        });
        return ionData;
    }

    public DataBundle createDataBundleFromParams(Map<String, String> params) {
        DataBundle pdb = new DataBundle();
        pdb.setMiddlewareSettings(middlewareSettings);
        PatientRecord patientRecord = new PatientRecord(
                0, // Assuming frameNumber as 0
                params.getOrDefault("pId", "Unknown"), // Default patient ID if not provided
                null, // additionalId not provided
                "Unknown Patient", // Default patient name
                null, // patientSecondName not provided
                null, // patientSex not provided
                null, // race not provided
                null, // dob not provided
                null, // patientAddress not provided
                null, // patientPhoneNumber not provided
                null // attendingDoctor not provided
        );
        pdb.setPatientRecord(patientRecord);
        logger.info("Patient record created for patient ID: " + patientRecord.getPatientId());

        // Decoding URL-encoded keys and rebuilding the map with decoded keys
        Map<String, String> decodedParams = new HashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            try {
                String decodedKey = URLDecoder.decode(entry.getKey(), StandardCharsets.UTF_8.name());
                decodedParams.put(decodedKey, entry.getValue());
            } catch (Exception e) {
                logger.error("Error decoding parameter key: " + entry.getKey(), e);
            }
        }

        // List of expected ions based on your data structure
        String[] expectedIons = {"Na", "K", "Cl", "Ca"};
        for (String ion : expectedIons) {
            logger.debug("Checking ion: " + ion);
            if (decodedParams.containsKey("ionData[" + ion + "][ion]")) {
                Map<String, String> ionData = parseIonData(decodedParams, ion);
                if (!ionData.isEmpty()) {
                    logger.debug("Ion data for " + ion + ": " + ionData);
                    ResultsRecord resultsRecord = new ResultsRecord(
                            0, // frameNumber
                            ion, // Test Code
                            ionData.getOrDefault("conc", "0"), // Result Value
                            Double.parseDouble(ionData.getOrDefault("min", "0")), // Minimum Value
                            Double.parseDouble(ionData.getOrDefault("max", "0")), // Maximum Value
                            ionData.getOrDefault("flag", ""), // Flag
                            ionData.getOrDefault("sampleType", ""), // Sample Type
                            ionData.getOrDefault("strUnits", ""), // Result Units
                            null, // Result DateTime
                            null, // Instrument Name
                            params.get("pId") // Sample ID
                    );
                    pdb.getResultsRecords().add(resultsRecord);
                } else {
                    logger.warn("No data found for ion: " + ion);
                }
            } else {
                logger.warn("Data for ion " + ion + " is not present in parameters.");
            }
        }

        logger.info("Total Result Records Created: " + pdb.getResultsRecords().size());
        return pdb;
    }

    public void pushResults(DataBundle dataBundle) {
        try {
            String pushResultsEndpoint = middlewareSettings.getLimsSettings().getLimsServerBaseUrl() + "/test_results";
            URL url = new URL(pushResultsEndpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            String jsonInputString = gson.toJson(dataBundle);
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                logger.info("Response from server: " + response.toString());
            }
        } catch (Exception e) {
            logger.error("Failed to send results to LIMS", e);
        }
    }
}
