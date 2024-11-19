package com.krickert.search.model.test.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;
import org.apache.commons.lang3.time.DateUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JsonToProtoStructConverter {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String[] DATE_FORMATS = new String[] {
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd"
    };

    public static List<Struct> convertJsonToStruct(String jsonString) throws IOException {
        JsonNode jsonNode = objectMapper.readTree(jsonString);
        if (jsonNode.isArray()) {
            return convertJsonArrayToStructList(jsonNode);
        } else {
            List<Struct> singleStructList = new ArrayList<>();
            singleStructList.add(convertJsonNodeToStruct(jsonNode, null));
            return singleStructList;
        }
    }

    public static List<Struct> convertJsonToStruct(String jsonString, String regex) throws IOException {
        JsonNode jsonNode = objectMapper.readTree(jsonString);
        Pattern pattern = (regex != null) ? Pattern.compile(regex) : null;
        if (jsonNode.isArray()) {
            return convertJsonArrayToStructList(jsonNode, pattern);
        } else {
            List<Struct> singleStructList = new ArrayList<>();
            singleStructList.add(convertJsonNodeToStruct(jsonNode, pattern));
            return singleStructList;
        }
    }

    private static List<Struct> convertJsonArrayToStructList(JsonNode jsonArrayNode) {
        return convertJsonArrayToStructList(jsonArrayNode, null);
    }

    private static List<Struct> convertJsonArrayToStructList(JsonNode jsonArrayNode, Pattern pattern) {
        List<Struct> structList = new ArrayList<>();
        for (JsonNode element : jsonArrayNode) {
            structList.add(convertJsonNodeToStruct(element, pattern));
        }
        return structList;
    }

    private static Struct convertJsonNodeToStruct(JsonNode jsonNode, Pattern pattern) {
        Struct.Builder structBuilder = Struct.newBuilder();

        Iterator<Map.Entry<String, JsonNode>> fields = jsonNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (pattern == null || !pattern.matcher(field.getKey()).matches()) {
                structBuilder.putFields(field.getKey(), convertJsonNodeToValue(field.getValue(), pattern));
            }
        }

        return structBuilder.build();
    }

    private static Value convertJsonNodeToValue(JsonNode jsonNode, Pattern pattern) {
        Value.Builder valueBuilder = Value.newBuilder();

        if (jsonNode.isNull()) {
            valueBuilder.setNullValueValue(0);
        } else if (jsonNode.isNumber()) {
            if (jsonNode.isInt() || jsonNode.isLong()) {
                valueBuilder.setNumberValue(jsonNode.longValue());
            } else if (jsonNode.isDouble()) {
                valueBuilder.setNumberValue(jsonNode.doubleValue());
            }
        } else if (jsonNode.isTextual()) {
            String textValue = jsonNode.textValue();
            try {
                // Try parsing as Instant
                Instant instant = Instant.parse(textValue);
                Timestamp timestamp = Timestamps.fromMillis(instant.toEpochMilli());
                valueBuilder.setStringValue(Timestamps.toString(timestamp));
            } catch (DateTimeParseException e) {
                try {
                    // Try parsing with multiple date formats
                    Date date = DateUtils.parseDate(textValue, DATE_FORMATS);
                    Timestamp timestamp = Timestamps.fromMillis(date.getTime());
                    valueBuilder.setStringValue(Timestamps.toString(timestamp));
                } catch (ParseException ex) {
                    valueBuilder.setStringValue(textValue);
                }
            }
        } else if (jsonNode.isBoolean()) {
            valueBuilder.setBoolValue(jsonNode.booleanValue());
        } else if (jsonNode.isObject()) {
            valueBuilder.setStructValue(convertJsonNodeToStruct(jsonNode, pattern));
        } else if (jsonNode.isArray()) {
            ListValue.Builder listValueBuilder = ListValue.newBuilder();
            for (JsonNode element : jsonNode) {
                listValueBuilder.addValues(convertJsonNodeToValue(element, pattern));
            }
            valueBuilder.setListValue(listValueBuilder);
        }

        return valueBuilder.build();
    }

    public static List<Struct> loadJsonFromResource(String resourceName) throws IOException {
        InputStream inputStream = JsonToProtoStructConverter.class.getClassLoader().getResourceAsStream(resourceName);
        if (inputStream == null) {
            Path path = Path.of(resourceName);
            if (Files.exists(path)) {
                inputStream = Files.newInputStream(path);
            } else {
                throw new IOException("Resource not found: " + resourceName);
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String jsonString = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            return convertJsonToStruct(jsonString);
        }
    }

    public static String convertStructToJson(Struct struct) throws IOException {
        JsonFormat.Printer printer = JsonFormat.printer();
        String jsonString = printer.print(struct);
        JsonNode jsonNode = objectMapper.readTree(jsonString);

        // Fix timestamp fields by converting them back to string representations
        Iterator<Map.Entry<String, JsonNode>> fields = jsonNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode valueNode = field.getValue();
            if (valueNode.isObject() && valueNode.has("timestamp")) {
                field.setValue(objectMapper.getNodeFactory().textNode(valueNode.get("timestamp").asText()));
            }
        }

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
    }

    public static void main(String[] args) {
        String json = "{" +
                "\"id\": 1234," +
                "\"title\": \"Sample Document\"," +
                "\"body\": \"This is a test document\"," +
                "\"keywords\": [\"sample\", \"test\"]," +
                "\"creation_date\": \"2024-01-01T00:00:00Z\"," +
                "\"last_updated_date\": \"2024-10-28T12:00:00Z\"," +
                "\"is_published\": true," +
                "\"_version_\": 1.0," +
                "\"custom_data\": {\"additional_field\": \"custom_value\"}" +
                "}";

        try {
            List<Struct> structs = convertJsonToStruct(json, "_version_");
            for (Struct struct : structs) {
                System.out.println(JsonFormat.printer().print(struct));
                String originalJson = convertStructToJson(struct);
                System.out.println("Converted back to JSON:\n" + originalJson);
            }

            try {
                List<Struct> resourceStructs = loadJsonFromResource("example.json");
                for (Struct resourceStruct : resourceStructs) {
                    System.out.println(JsonFormat.printer().print(resourceStruct));
                    String originalJson = convertStructToJson(resourceStruct);
                    System.out.println("Converted back to JSON:\n" + originalJson);
                }
            } catch (IOException e) {
                System.err.println("Warning: " + e.getMessage());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
