package com.graphhopper.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.NearestRequest;

import java.io.IOException;

class NearestRequestDeserializer extends JsonDeserializer<NearestRequest> {
    @Override
    public NearestRequest deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
            throws IOException {
        NearestRequest request = new NearestRequest();
        JsonNode treeNode = jsonParser.readValueAsTree();
        for (JsonNode pointNode : treeNode.get("points")) {
            request.addPoint(
                    new GHPoint(pointNode.get("latitude").asDouble(), pointNode.get("longitude").asDouble()));
        }
        return request;
    }
}