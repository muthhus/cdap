/*
 * Copyright © 2017 Cask Data, Inc.
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
package io.cdap.cdap.proto.codec;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import io.cdap.cdap.api.workflow.ConditionSpecification;
import io.cdap.cdap.internal.workflow.condition.DefaultConditionSpecification;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;

/**
 * Codec to serialize and deserialize {@link ConditionSpecification}.
 */
public class ConditionSpecificationCodec extends
    AbstractSpecificationCodec<ConditionSpecification> {

  @Override
  public ConditionSpecification deserialize(JsonElement json, Type typeOfT,
      JsonDeserializationContext context)
      throws JsonParseException {
    JsonObject jsonObj = json.getAsJsonObject();

    String className = jsonObj.get("className").getAsString();
    String name = jsonObj.get("name").getAsString();
    String description = jsonObj.get("description").getAsString();
    Set<String> datasets = deserializeSet(jsonObj.get("datasets"), context, String.class);
    Map<String, String> properties = deserializeMap(jsonObj.get("properties"), context,
        String.class);
    return new DefaultConditionSpecification(className, name, description, properties, datasets);

  }

  @Override
  public JsonElement serialize(ConditionSpecification src, Type typeOfSrc,
      JsonSerializationContext context) {
    JsonObject jsonObj = new JsonObject();

    jsonObj.add("className", new JsonPrimitive(src.getClassName()));
    jsonObj.add("name", new JsonPrimitive(src.getName()));
    jsonObj.add("description", new JsonPrimitive(src.getDescription()));
    jsonObj.add("datasets", serializeSet(src.getDatasets(), context, String.class));
    jsonObj.add("properties", serializeMap(src.getProperties(), context, String.class));

    return jsonObj;
  }
}
