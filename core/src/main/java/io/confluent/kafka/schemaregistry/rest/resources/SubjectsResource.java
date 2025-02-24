/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.kafka.schemaregistry.rest.resources;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.rest.Versions;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaRequest;
import io.confluent.kafka.schemaregistry.exceptions.ReferenceExistsException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryStoreException;
import io.confluent.kafka.schemaregistry.exceptions.SubjectNotSoftDeletedException;
import io.confluent.kafka.schemaregistry.rest.exceptions.Errors;
import io.confluent.kafka.schemaregistry.storage.KafkaSchemaRegistry;
import io.confluent.kafka.schemaregistry.utils.QualifiedSubject;
import io.confluent.rest.annotations.PerformanceMetric;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/subjects")
@Produces({Versions.SCHEMA_REGISTRY_V1_JSON_WEIGHTED,
           Versions.SCHEMA_REGISTRY_DEFAULT_JSON_WEIGHTED,
           Versions.JSON_WEIGHTED})
@Consumes({Versions.SCHEMA_REGISTRY_V1_JSON,
           Versions.SCHEMA_REGISTRY_DEFAULT_JSON,
           Versions.JSON, Versions.GENERIC_REQUEST})
public class SubjectsResource {

  private static final Logger log = LoggerFactory.getLogger(SubjectsResource.class);
  private final KafkaSchemaRegistry schemaRegistry;
  private final RequestHeaderBuilder requestHeaderBuilder = new RequestHeaderBuilder();

  public SubjectsResource(KafkaSchemaRegistry schemaRegistry) {
    this.schemaRegistry = schemaRegistry;
  }

  @POST
  @Path("/{subject}")
  @Operation(summary = "Check if a schema has already been registered under the specified subject."
      + " If so, this returns the schema string along with its globally unique identifier, its "
      + "version under this subject and the subject name.", responses = {
        @ApiResponse(content = @Content(schema =
          @io.swagger.v3.oas.annotations.media.Schema(implementation = Schema.class))),
        @ApiResponse(responseCode = "404", description = "Error code 40401 -- Subject not found\n"
          + "Error code 40403 -- Schema not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
      })
  @PerformanceMetric("subjects.get-schema")
  public void lookUpSchemaUnderSubject(
      final @Suspended AsyncResponse asyncResponse,
      @Parameter(description = "Subject under which the schema will be registered", required = true)
      @PathParam("subject") String subject,
      @QueryParam("deleted") boolean lookupDeletedSchema,
      @Parameter(description = "Schema", required = true)
      @NotNull RegisterSchemaRequest request) {
    log.info("Schema lookup under subject {}, deleted {}, type {}",
             subject, lookupDeletedSchema, request.getSchemaType());

    subject = QualifiedSubject.normalize(schemaRegistry.tenant(), subject);

    // returns version if the schema exists. Otherwise returns 404
    Schema schema = new Schema(
        subject,
        0,
        -1,
        request.getSchemaType() != null ? request.getSchemaType() : AvroSchema.TYPE,
        request.getReferences(),
        request.getSchema()
    );
    io.confluent.kafka.schemaregistry.client.rest.entities.Schema matchingSchema;
    try {
      if (!schemaRegistry.hasSubjects(subject, lookupDeletedSchema)) {
        throw Errors.subjectNotFoundException(subject);
      }
      matchingSchema =
          schemaRegistry.lookUpSchemaUnderSubject(subject, schema, lookupDeletedSchema);
    } catch (SchemaRegistryException e) {
      throw Errors.schemaRegistryException("Error while looking up schema under subject " + subject,
                                           e);
    }
    if (matchingSchema == null) {
      throw Errors.schemaNotFoundException();
    }
    asyncResponse.resume(matchingSchema);
  }

  @GET
  @Valid
  @Operation(summary = "Get a list of registered subjects.", responses = {
      @ApiResponse(responseCode = "500", description = "Error code 50001 -- Error in the backend "
          + "datastore")
  })
  @PerformanceMetric("subjects.list")
  public Set<String> list(
      @DefaultValue("") @QueryParam("subjectPrefix") String subjectPrefix,
      @QueryParam("deleted") boolean lookupDeletedSubjects
  ) {
    try {
      return schemaRegistry.listSubjectsWithPrefix(
          subjectPrefix != null ? subjectPrefix : "", lookupDeletedSubjects);
    } catch (SchemaRegistryStoreException e) {
      throw Errors.storeException("Error while listing subjects", e);
    } catch (SchemaRegistryException e) {
      throw Errors.schemaRegistryException("Error while listing subjects", e);
    }
  }

  @DELETE
  @Path("/{subject}")
  @Operation(summary = "Deletes the specified subject and its associated compatibility level if "
      + "registered. It is recommended to use this API only when a topic needs to be recycled or "
      + "in development environment.", responses = {
        @ApiResponse(content = @Content(array = @ArraySchema(schema =
          @io.swagger.v3.oas.annotations.media.Schema(implementation = Integer.class)))),
        @ApiResponse(responseCode = "404", description = "Error code 40401 -- Subject not found"),
        @ApiResponse(responseCode = "500",
          description = "Error code 50001 -- Error in the backend datastore")
      })
  @PerformanceMetric("subjects.delete-subject")
  public void deleteSubject(
      final @Suspended AsyncResponse asyncResponse,
      @Context HttpHeaders headers,
      @Parameter(description = "the name of the subject", required = true)
      @PathParam("subject") String subject,
      @QueryParam("permanent") boolean permanentDelete) {
    log.info("Deleting subject {}", subject);

    subject = QualifiedSubject.normalize(schemaRegistry.tenant(), subject);

    List<Integer> deletedVersions;
    try {
      if (!schemaRegistry.hasSubjects(subject, true)) {
        throw Errors.subjectNotFoundException(subject);
      }
      if (!permanentDelete && !schemaRegistry.hasSubjects(subject, false)) {
        throw Errors.subjectSoftDeletedException(subject);
      }
      Map<String, String> headerProperties = requestHeaderBuilder.buildRequestHeaders(
          headers, schemaRegistry.config().whitelistHeaders());
      deletedVersions = schemaRegistry.deleteSubjectOrForward(headerProperties,
              subject,
              permanentDelete);
    } catch (ReferenceExistsException e) {
      throw Errors.referenceExistsException(e.getMessage());
    } catch (SubjectNotSoftDeletedException e) {
      throw Errors.subjectNotSoftDeletedException(subject);
    } catch (SchemaRegistryException e) {
      throw Errors.schemaRegistryException("Error while deleting the subject " + subject,
                                           e);
    }
    asyncResponse.resume(deletedVersions);
  }

}
