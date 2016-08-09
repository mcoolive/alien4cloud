package alien4cloud.rest.topology;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Map.Entry;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.validation.Valid;

import org.alien4cloud.tosca.editor.TopologyDTOBuilder;
import org.alien4cloud.tosca.editor.EditionContextManager;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;

import alien4cloud.application.ApplicationVersionService;
import alien4cloud.application.TopologyCompositionService;
import alien4cloud.component.CSARRepositorySearchService;
import alien4cloud.component.repository.ArtifactRepositoryConstants;
import alien4cloud.component.repository.IFileRepository;
import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.exception.AlreadyExistException;
import alien4cloud.exception.CyclicReferenceException;
import alien4cloud.exception.InvalidNodeNameException;
import alien4cloud.exception.NotFoundException;
import alien4cloud.model.components.*;
import alien4cloud.model.templates.TopologyTemplate;
import alien4cloud.model.topology.*;
import alien4cloud.paas.plan.TopologyTreeBuilderService;
import alien4cloud.paas.wf.WorkflowsBuilderService;
import alien4cloud.paas.wf.WorkflowsBuilderService.TopologyContext;
import alien4cloud.rest.model.RestErrorBuilder;
import alien4cloud.rest.model.RestErrorCode;
import alien4cloud.rest.model.RestResponse;
import alien4cloud.rest.model.RestResponseBuilder;
import alien4cloud.security.model.ApplicationRole;
import alien4cloud.topology.*;
import alien4cloud.topology.validation.TopologyCapabilityBoundsValidationServices;
import alien4cloud.topology.validation.TopologyRequirementBoundsValidationServices;
import alien4cloud.tosca.properties.constraints.ConstraintUtil.ConstraintInformation;
import alien4cloud.tosca.properties.constraints.exception.ConstraintValueDoNotMatchPropertyTypeException;
import alien4cloud.tosca.properties.constraints.exception.ConstraintViolationException;
import alien4cloud.tosca.topology.NodeTemplateBuilder;
import alien4cloud.utils.InputArtifactUtil;
import alien4cloud.utils.RestConstraintValidator;
import alien4cloud.utils.services.PropertyService;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping({ "/rest/topologies", "/rest/v1/topologies", "/rest/latest/topologies" })
public class TopologyController {

    @Resource(name = "alien-es-dao")
    private IGenericSearchDAO alienDAO;

    @Resource
    private CSARRepositorySearchService csarRepoSearch;

    @Inject
    private PropertyService propertyService;

    @Resource
    private TopologyService topologyService;

    @Resource
    private TopologyValidationService topologyValidationService;

    @Resource
    private TopologyCapabilityBoundsValidationServices topologyCapabilityBoundsValidationServices;

    @Resource
    private TopologyRequirementBoundsValidationServices topologyRequirementBoundsValidationServices;

    @Resource
    private TopologyServiceCore topologyServiceCore;

    @Resource
    private TopologyTreeBuilderService topologyTreeBuilderService;

    @Resource
    private IFileRepository artifactRepository;

    @Resource
    private ApplicationVersionService applicationVersionService;

    @Resource
    private TopologyTemplateVersionService topologyTemplateVersionService;

    @Resource
    private TopologyCompositionService topologyCompositionService;

    @Resource
    private WorkflowsBuilderService workflowBuilderService;

    @Resource
    private EditionContextManager topologyEditionContextManager;
    @Inject
    private TopologyDTOBuilder dtoBuilder;

    /**
     * Retrieve an existing {@link alien4cloud.model.topology.Topology}
     *
     * @param topologyId The id of the topology to retrieve.
     * @return {@link RestResponse}<{@link TopologyDTO}> containing the {@link alien4cloud.model.topology.Topology} and the {@link IndexedNodeType} related
     *         to his {@link alien4cloud.model.topology.NodeTemplate}s
     */
    @ApiOperation(value = "Retrieve a topology from it's id.", notes = "Returns a topology with it's details. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> get(@PathVariable String topologyId) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkAuthorizations(topology, ApplicationRole.APPLICATION_MANAGER, ApplicationRole.APPLICATION_DEVOPS,
                ApplicationRole.APPLICATION_USER);
        try {
            topologyEditionContextManager.init(topologyId);
            return RestResponseBuilder.<TopologyDTO> builder().data(dtoBuilder.buildTopologyDTO(EditionContextManager.get())).build();
        } finally {
            topologyEditionContextManager.destroy();
        }
    }

    /**
     * Retrieve an existing {@link alien4cloud.model.topology.Topology} as YAML
     *
     * @param topologyId The id of the topology to retrieve.
     * @return {@link RestResponse}<{@link String}> containing the topology as YAML
     */
    @RequestMapping(value = "/{topologyId}/yaml", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<String> getYaml(@PathVariable String topologyId) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkAuthorizations(topology, ApplicationRole.APPLICATION_MANAGER, ApplicationRole.APPLICATION_DEVOPS,
                ApplicationRole.APPLICATION_USER);
        String yaml = topologyService.getYaml(topology);
        return RestResponseBuilder.<String> builder().data(yaml).build();
    }

    /**
     * Add a node template to a topology based on a node type
     *
     * @param topologyId
     *            The id of the topology for which to add the node template.
     * @param nodeTemplateRequest
     *            The request that contains the name and type of the node template to add.
     * @return TopologyDTO The DTO of the modified topology.
     */
    @ApiOperation(value = "Add a new node template in a topology.", notes = "Returns the details of the node template (computed from it's type). Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> addNodeTemplate(@PathVariable String topologyId, @RequestBody @Valid NodeTemplateRequest nodeTemplateRequest) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        if (!TopologyUtils.isValidNodeName(nodeTemplateRequest.getName())) {
            throw new InvalidNodeNameException("A name should only contains alphanumeric character from the basic Latin alphabet and the underscore.");
        }
        if (topology.getNodeTemplates() != null) {
            topologyService.isUniqueNodeTemplateName(topology, nodeTemplateRequest.getName());
        }

        IndexedNodeType indexedNodeType = alienDAO.findById(IndexedNodeType.class, nodeTemplateRequest.getIndexedNodeTypeId());
        if (indexedNodeType == null) {
            return RestResponseBuilder.<TopologyDTO> builder().error(RestErrorBuilder.builder(RestErrorCode.COMPONENT_MISSING_ERROR).build()).build();
        }
        if (indexedNodeType.getSubstitutionTopologyId() != null && topology.getDelegateType().equalsIgnoreCase(TopologyTemplate.class.getSimpleName())) {
            // it's a try to add this topology's type
            if (indexedNodeType.getSubstitutionTopologyId().equals(topologyId)) {
                throw new CyclicReferenceException("Cyclic reference : a topology template can not reference itself");
            }
            // detect try to add a substitution topology that indirectly reference this one
            topologyCompositionService.recursivelyDetectTopologyCompositionCyclicReference(topologyId, indexedNodeType.getSubstitutionTopologyId());
        }

        if (topology.getNodeTemplates() == null) {
            topology.setNodeTemplates(new HashMap<String, NodeTemplate>());
        }

        Map<String, NodeTemplate> nodeTemplates = topology.getNodeTemplates();
        Set<String> nodeTemplatesNames = nodeTemplates.keySet();
        if (nodeTemplatesNames.contains(nodeTemplateRequest.getName())) {
            log.debug("Add Node Template <{}> impossible (already exists)", nodeTemplateRequest.getName());
            // a node template already exist with the given name.
            throw new AlreadyExistException("A node template with the given name already exists.");
        } else {
            log.debug("Create node template <{}>", nodeTemplateRequest.getName());
        }
        indexedNodeType = topologyService.loadType(topology, indexedNodeType);
        NodeTemplate nodeTemplate = topologyService.buildNodeTemplate(topology.getDependencies(), indexedNodeType, null);
        nodeTemplate.setName(nodeTemplateRequest.getName());
        topology.getNodeTemplates().put(nodeTemplateRequest.getName(), nodeTemplate);

        log.debug("Adding a new Node template <" + nodeTemplateRequest.getName() + "> bound to the node type <" + nodeTemplateRequest.getIndexedNodeTypeId()
                + "> to the topology <" + topology.getId() + "> .");

        TopologyContext topologyContext = workflowBuilderService.buildTopologyContext(topology);
        workflowBuilderService.addNode(topologyContext, nodeTemplateRequest.getName(), nodeTemplate);
        topologyServiceCore.save(topology);
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    /**
     * Update the name of a node template.
     *
     * @param topologyId The id of the topology in which the node template to update lies.
     * @param nodeTemplateName The name of the node template to update.
     * @param newNodeTemplateName The new name for the node template.
     * @return {@link RestResponse}<{@link TopologyDTO}> an response with no data and no error if successful.
     */
    @ApiOperation(value = "Change the name of a node template in a topology.", notes = "Returns a response with no errors in case of success. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/updateName/{newNodeTemplateName}", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> updateNodeTemplateName(@PathVariable String topologyId, @PathVariable String nodeTemplateName,
            @PathVariable String newNodeTemplateName) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        if (!TopologyUtils.isValidNodeName(newNodeTemplateName)) {
            throw new InvalidNodeNameException("A name should only contains alphanumeric character from the basic Latin alphabet and the underscore.");
        }
        // ensure there is node templates
        TopologyServiceCore.getNodeTemplates(topology);
        topologyService.isUniqueNodeTemplateName(topology, newNodeTemplateName);

        TopologyUtils.renameNodeTemplate(topology, nodeTemplateName, newNodeTemplateName);
        workflowBuilderService.renameNode(topology, nodeTemplateName, newNodeTemplateName);
        log.debug("Renaming the Node template <{}> with <{}> in the topology <{}> .", nodeTemplateName, newNodeTemplateName, topology.getId());
        topologyServiceCore.save(topology);
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    /**
     * Add a new {@link RelationshipTemplate} to a {@link NodeTemplate} in a {@link Topology}.
     *
     * @param topologyId The id of the topology in which the node template lies.
     * @param nodeTemplateName The name of the node template to which we should add the relationship.
     * @param relationshipName The name of the relationship to add.
     * @param relationshipTemplateRequest The relationship.
     * @return A rest response with no errors if successful.
     */
    @ApiOperation(value = "Add a relationship to a node template.", notes = "Returns a response with no errors in case of success. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId}/nodetemplates/{nodeTemplateName}/relationships/{relationshipName}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> addRelationshipTemplate(@PathVariable String topologyId, @PathVariable String nodeTemplateName,
            @PathVariable String relationshipName, @RequestBody AddRelationshipTemplateRequest relationshipTemplateRequest) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);

        IndexedRelationshipType indexedRelationshipType = alienDAO.findById(IndexedRelationshipType.class,
                relationshipTemplateRequest.getRelationshipTemplate().getType() + ":" + relationshipTemplateRequest.getArchiveVersion());
        if (indexedRelationshipType == null) {
            return RestResponseBuilder.<TopologyDTO> builder().error(RestErrorBuilder.builder(RestErrorCode.COMPONENT_MISSING_ERROR).build()).build();
        }
        topologyService.loadType(topology, indexedRelationshipType);
        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);
        NodeTemplate nodeTemplate = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);

        boolean upperBoundReachedSource = topologyRequirementBoundsValidationServices.isRequirementUpperBoundReachedForSource(nodeTemplate,
                relationshipTemplateRequest.getRelationshipTemplate().getRequirementName(), topology.getDependencies());
        // return with a rest response error
        if (upperBoundReachedSource) {
            return RestResponseBuilder.<TopologyDTO> builder()
                    .error(RestErrorBuilder
                            .builder(RestErrorCode.UPPER_BOUND_REACHED).message("UpperBound reached on requirement <"
                                    + relationshipTemplateRequest.getRelationshipTemplate().getRequirementName() + "> on node <" + nodeTemplateName + ">.")
                            .build())
                    .build();
        }

        boolean upperBoundReachedTarget = topologyCapabilityBoundsValidationServices.isCapabilityUpperBoundReachedForTarget(
                relationshipTemplateRequest.getRelationshipTemplate().getTarget(), nodeTemplates,
                relationshipTemplateRequest.getRelationshipTemplate().getTargetedCapabilityName(), topology.getDependencies());
        // return with a rest response error
        if (upperBoundReachedTarget) {
            return RestResponseBuilder
                    .<TopologyDTO> builder().error(
                            RestErrorBuilder.builder(RestErrorCode.UPPER_BOUND_REACHED)
                                    .message("UpperBound reached on capability <"
                                            + relationshipTemplateRequest.getRelationshipTemplate().getTargetedCapabilityName() + "> on node <"
                                            + relationshipTemplateRequest.getRelationshipTemplate().getTarget() + ">.")
                                    .build())
                    .build();
        }

        Map<String, RelationshipTemplate> relationships = nodeTemplate.getRelationships();
        if (relationships == null) {
            relationships = Maps.newHashMap();
            nodeTemplates.get(nodeTemplateName).setRelationships(relationships);
        }

        RelationshipTemplate relationship = relationshipTemplateRequest.getRelationshipTemplate();
        Map<String, AbstractPropertyValue> properties = Maps.newHashMap();
        NodeTemplateBuilder.fillProperties(properties, indexedRelationshipType.getProperties(), null);
        relationship.setProperties(properties);
        relationship.setAttributes(indexedRelationshipType.getAttributes());
        relationships.put(relationshipName, relationship);
        TopologyContext topologyContext = workflowBuilderService.buildTopologyContext(topology);
        workflowBuilderService.addRelationship(topologyContext, nodeTemplateName, relationshipName);
        topologyServiceCore.save(topology);
        log.info("Added relationship to the topology [" + topologyId + "], node name [" + nodeTemplateName + "], relationship name [" + relationshipName + "]");
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    /**
     * Remove a nodeTemplate outputs in a topology
     */
    private void removeOutputs(String nodeTemplateName, Topology topology) {
        if (topology.getOutputProperties() != null) {
            topology.getOutputProperties().remove(nodeTemplateName);
        }
        if (topology.getOutputAttributes() != null) {
            topology.getOutputAttributes().remove(nodeTemplateName);
        }
    }

    /**
     * Delete a node template from a topology
     *
     * @param topologyId Id of the topology from which to delete the node template.
     * @param nodeTemplateName Id of the node template to delete.
     * @return NodeTemplateDTO The DTO containing the newly deleted node template and the related node type
     */
    @ApiOperation(value = "Delete a node tempalte from a topology", notes = "If successful returns a result containing the list of impacted nodes (that will loose relationships). Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> deleteNodeTemplate(@PathVariable String topologyId, @PathVariable String nodeTemplateName) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        log.debug("Removing the Node template <{}> from the topology <{}> .", nodeTemplateName, topology.getId());

        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);

        NodeTemplate template = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);
        // Clean up internal repository
        Map<String, DeploymentArtifact> artifacts = template.getArtifacts();
        if (artifacts != null) {
            for (Map.Entry<String, DeploymentArtifact> artifactEntry : artifacts.entrySet()) {
                DeploymentArtifact artifact = artifactEntry.getValue();
                if (ArtifactRepositoryConstants.ALIEN_ARTIFACT_REPOSITORY.equals(artifact.getArtifactRepository())) {
                    this.artifactRepository.deleteFile(artifact.getArtifactRef());
                }
            }
        }
        List<String> typesTobeUnloaded = Lists.newArrayList();
        // Clean up dependencies of the topology
        typesTobeUnloaded.add(template.getType());
        if (template.getRelationships() != null) {
            for (RelationshipTemplate relationshipTemplate : template.getRelationships().values()) {
                typesTobeUnloaded.add(relationshipTemplate.getType());
            }
        }
        topologyService.unloadType(topology, typesTobeUnloaded.toArray(new String[typesTobeUnloaded.size()]));
        removeRelationShipReferences(nodeTemplateName, topology);
        nodeTemplates.remove(nodeTemplateName);
        removeOutputs(nodeTemplateName, topology);
        if (topology.getSubstitutionMapping() != null) {
            removeNodeTemplateSubstitutionTargetMapEntry(nodeTemplateName, topology.getSubstitutionMapping().getCapabilities());
            removeNodeTemplateSubstitutionTargetMapEntry(nodeTemplateName, topology.getSubstitutionMapping().getRequirements());
        }

        // group members removal
        TopologyUtils.updateGroupMembers(topology, template, nodeTemplateName, null);
        // update the workflows
        workflowBuilderService.removeNode(topology, nodeTemplateName, template);
        topologyServiceCore.save(topology);
        topologyServiceCore.updateSubstitutionType(topology);
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    private void removeNodeTemplateSubstitutionTargetMapEntry(String nodeTemplateName, Map<String, SubstitutionTarget> substitutionTargets) {
        if (substitutionTargets == null) {
            return;
        }
        Iterator<Entry<String, SubstitutionTarget>> capabilities = substitutionTargets.entrySet().iterator();
        while (capabilities.hasNext()) {
            Entry<String, SubstitutionTarget> e = capabilities.next();
            if (e.getValue().getNodeTemplateName().equals(nodeTemplateName)) {
                capabilities.remove();
            }
        }
    }

    /**
     * Update one property for a given {@link NodeTemplate}
     *
     * @param topologyId The id of the topology that contains the node template for which to update a property.
     * @param nodeTemplateName The name of the node template for which to update a property.
     * @param updatePropertyRequest The key and value of the property to update. When value is null => "reset" (load the default value).
     * @return a void rest response that contains no data if successful and an error if something goes wrong.
     */
    @ApiOperation(value = "Update properties values.", notes = "Returns a topology with it's details. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/properties", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<ConstraintInformation> updatePropertyValue(@PathVariable String topologyId, @PathVariable String nodeTemplateName,
            @RequestBody UpdatePropertyRequest updatePropertyRequest) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);
        NodeTemplate nodeTemp = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);
        String propertyName = updatePropertyRequest.getPropertyName();
        Object propertyValue = updatePropertyRequest.getPropertyValue();

        IndexedNodeType node = csarRepoSearch.getElementInDependencies(IndexedNodeType.class, nodeTemp.getType(), topology.getDependencies());
        PropertyDefinition propertyDefinition = node.getProperties().get(propertyName);
        if (propertyDefinition == null) {
            throw new NotFoundException(
                    "Property <" + propertyName + "> doesn't exists for node <" + nodeTemplateName + "> of type <" + nodeTemp.getType() + ">");
        }

        log.debug("Updating property <{}> of the Node template <{}> from the topology <{}>: changing value from [{}] to [{}].", propertyName, nodeTemplateName,
                topology.getId(), nodeTemp.getProperties().get(propertyName), propertyValue);

        try {
            propertyService.setPropertyValue(nodeTemp, propertyDefinition, propertyName, propertyValue);
        } catch (ConstraintValueDoNotMatchPropertyTypeException | ConstraintViolationException e) {
            return RestConstraintValidator.fromException(e, propertyName, propertyValue);
        }
        topologyServiceCore.save(topology);
        return RestResponseBuilder.<ConstraintInformation> builder().build();
    }

    /**
     * Update one property for a given @{IndexedRelationshipType} of a {@link NodeTemplate}
     *
     * @param topologyId The id of the topology that contains the node template for which to update a property.
     * @param nodeTemplateName The name of the node template for which to update a property.
     * @param updatePropertyRequest The key and value of the property to update. When value is null => "reset" (load the default value).
     * @return a void rest response that contains no data if successful and an error if something goes wrong.
     */
    @ApiOperation(value = "Update a relationship property value.", notes = "Returns a topology with it's details. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/relationships/{relationshipName}/updateProperty", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<ConstraintInformation> updateRelationshipPropertyValue(@PathVariable String topologyId, @PathVariable String nodeTemplateName,
            @PathVariable String relationshipName, @RequestBody UpdateIndexedTypePropertyRequest updatePropertyRequest) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        String propertyName = updatePropertyRequest.getPropertyName();
        Object propertyValue = updatePropertyRequest.getPropertyValue();
        String relationshipType = updatePropertyRequest.getType();
        Map<String, IndexedRelationshipType> relationshipTypes = topologyServiceCore.getIndexedRelationshipTypesFromTopology(topology);

        if (!relationshipTypes.get(relationshipType).getProperties().containsKey(propertyName)) {
            throw new NotFoundException(
                    "Property <" + propertyName + "> doesn't exists for node <" + nodeTemplateName + "> of type <" + relationshipType + ">");
        }

        log.debug("Updating property <{}> of the relationship <{}> for the Node template <{}> from the topology <{}>: changing value from [{}] to [{}].",
                propertyName, relationshipType, nodeTemplateName, topology.getId(), relationshipTypes.get(relationshipType).getProperties().get(propertyName),
                propertyValue);

        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);
        NodeTemplate nodeTemplate = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);
        Map<String, RelationshipTemplate> relationships = nodeTemplate.getRelationships();

        try {
            propertyService.setPropertyValue(relationships.get(relationshipName).getProperties(),
                    relationshipTypes.get(relationshipType).getProperties().get(propertyName), propertyName, propertyValue);
        } catch (ConstraintValueDoNotMatchPropertyTypeException | ConstraintViolationException e) {
            return RestConstraintValidator.fromException(e, propertyName, propertyValue);
        }

        topologyServiceCore.save(topology);
        return RestResponseBuilder.<ConstraintInformation> builder().build();
    }

    /**
     * Update one property for a given @{IndexedCapabilityType} of a {@link NodeTemplate}
     *
     * @param topologyId The id of the topology that contains the node template for which to update a property.
     * @param nodeTemplateName The name of the node template for which to update a property.
     * @param capabilityId The name of the capability.
     * @param updatePropertyRequest The key and value of the property to update. When value is null => "reset" (load the default value).
     * @return a void rest response that contains no data if successful and an error if something goes wrong.
     */
    @ApiOperation(value = "Update a relationship property value.", notes = "Returns a topology with it's details. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/capability/{capabilityId}/updateProperty", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<ConstraintInformation> updateCapabilityPropertyValue(@PathVariable String topologyId, @PathVariable String nodeTemplateName,
            @PathVariable String capabilityId, @RequestBody UpdateIndexedTypePropertyRequest updatePropertyRequest) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        String propertyName = updatePropertyRequest.getPropertyName();
        Object propertyValue = updatePropertyRequest.getPropertyValue();
        String capabilityType = updatePropertyRequest.getType();
        Map<String, IndexedCapabilityType> capabilityTypes = topologyServiceCore.getIndexedCapabilityTypesFromTopology(topology);

        if (!capabilityTypes.get(capabilityType).getProperties().containsKey(propertyName)) {
            throw new NotFoundException("Property <" + propertyName + "> doesn't exists for node <" + nodeTemplateName + "> of type <" + capabilityType + ">");
        }

        log.debug("Updating property <{}> of the capability <{}> for the Node template <{}> from the topology <{}>: changing value from [{}] to [{}].",
                propertyName, capabilityType, nodeTemplateName, topology.getId(), capabilityTypes.get(capabilityType).getProperties().get(propertyName),
                propertyValue);

        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);
        NodeTemplate nodeTemplate = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);
        Map<String, Capability> capabilities = nodeTemplate.getCapabilities();

        try {
            propertyService.setPropertyValue(capabilities.get(capabilityId).getProperties(),
                    capabilityTypes.get(capabilityType).getProperties().get(propertyName), propertyName, propertyValue);
        } catch (ConstraintValueDoNotMatchPropertyTypeException | ConstraintViolationException e) {
            return RestConstraintValidator.fromException(e, propertyName, propertyValue);
        }

        topologyServiceCore.save(topology);
        return RestResponseBuilder.<ConstraintInformation> builder().build();
    }

    /**
     * check if a topology is valid or not.
     *
     * @param topologyId The id of the topology to check.
     * @return a boolean rest response that says if the topology is valid or not.
     */
    @ApiOperation(value = "Check if a topology is valid or not.", notes = "Returns true if valid, false if not. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/isvalid", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyValidationResult> isTopologyValid(@PathVariable String topologyId, @RequestParam(required = false) String environmentId) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkAuthorizations(topology, ApplicationRole.APPLICATION_MANAGER, ApplicationRole.APPLICATION_DEVOPS,
                ApplicationRole.APPLICATION_USER);
        TopologyValidationResult dto = topologyValidationService.validateTopology(topology);
        return RestResponseBuilder.<TopologyValidationResult> builder().data(dto).build();
    }

    /**
     * Get possible replacement indexedNodeTypes for a node template
     *
     * @param topologyId The id of the topology to check.
     * @param nodeTemplateName The name of the node template to check for replacement.
     * @return An array of indexedNodeType which can replace the node template
     */
    @ApiOperation(value = "Get possible replacement indexedNodeTypes for a node template.", notes = "Returns An array of indexedNodeType which can replace the node template. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/replace", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<IndexedNodeType[]> getReplacementForNode(@PathVariable String topologyId, @PathVariable String nodeTemplateName) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);

        TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, TopologyServiceCore.getNodeTemplates(topology));

        IndexedNodeType[] replacementsNodeTypes = topologyService.findReplacementForNode(nodeTemplateName, topology);

        return RestResponseBuilder.<IndexedNodeType[]> builder().data(replacementsNodeTypes).build();
    }

    /**
     * Replace a node template
     */
    @ApiOperation(value = "Replace a node template possible with another one.", notes = "Returns the details of the new node template (computed from it's type). Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/replace", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> replaceNodeTemplate(@PathVariable String topologyId, @PathVariable String nodeTemplateName,
            @RequestBody @Valid NodeTemplateRequest nodeTemplateRequest) {
        // FIXME should we remove outputs here ?
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);

        IndexedNodeType indexedNodeType = findIndexedNodeType(nodeTemplateRequest.getIndexedNodeTypeId());

        // Retrieve existing node template
        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);
        NodeTemplate oldNodeTemplate = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);
        // Load the new type to the topology in order to update its dependencies
        indexedNodeType = topologyService.loadType(topology, indexedNodeType);
        // Build the new one
        NodeTemplate newNodeTemplate = topologyService.buildNodeTemplate(topology.getDependencies(), indexedNodeType, null);
        newNodeTemplate.setName(nodeTemplateRequest.getName());
        newNodeTemplate.setRelationships(oldNodeTemplate.getRelationships());
        // Put the new one in the topology
        nodeTemplates.put(nodeTemplateRequest.getName(), newNodeTemplate);

        // Unload and remove old node template
        topologyService.unloadType(topology, oldNodeTemplate.getType());
        // remove the node from the workflows
        workflowBuilderService.removeNode(topology, nodeTemplateName, oldNodeTemplate);
        nodeTemplates.remove(nodeTemplateName);
        if (topology.getSubstitutionMapping() != null) {
            removeNodeTemplateSubstitutionTargetMapEntry(nodeTemplateName, topology.getSubstitutionMapping().getCapabilities());
            removeNodeTemplateSubstitutionTargetMapEntry(nodeTemplateName, topology.getSubstitutionMapping().getRequirements());
        }

        TopologyUtils.refreshNodeTempNameInRelationships(nodeTemplateName, nodeTemplateRequest.getName(), nodeTemplates);
        log.debug("Replacing the node template<{}> with <{}> bound to the node type <{}> on the topology <{}> .", nodeTemplateName,
                nodeTemplateRequest.getName(), nodeTemplateRequest.getIndexedNodeTypeId(), topology.getId());
        // add the new node to the workflow
        workflowBuilderService.addNode(workflowBuilderService.buildTopologyContext(topology), nodeTemplateRequest.getName(), newNodeTemplate);

        topologyServiceCore.save(topology);
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    /**
     * Update application's artifact.
     *
     * @param topologyId The topology's id
     * @param nodeTemplateName The node template's name
     * @param artifactId artifact's id
     * @return nothing if success, error will be handled in global exception strategy
     * @throws IOException
     */
    @ApiOperation(value = "Updates the deployment artifact of the node template.", notes = "The logged-in user must have the application manager role for this application. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/artifacts/{artifactId}", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> updateDeploymentArtifact(@PathVariable String topologyId, @PathVariable String nodeTemplateName,
            @PathVariable String artifactId, @RequestParam("file") MultipartFile artifactFile) throws IOException {
        // Perform check that authorization's ok
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        // Get the node template's artifacts to update
        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);
        NodeTemplate nodeTemplate = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);
        Map<String, DeploymentArtifact> artifacts = nodeTemplate.getArtifacts();
        if (artifacts == null) {
            throw new NotFoundException("Artifact with key [" + artifactId + "] do not exist");
        }
        DeploymentArtifact artifact = artifacts.get(artifactId);
        if (artifact == null) {
            throw new NotFoundException("Artifact with key [" + artifactId + "] do not exist");
        }
        String oldArtifactId = artifact.getArtifactRef();
        if (ArtifactRepositoryConstants.ALIEN_ARTIFACT_REPOSITORY.equals(artifact.getArtifactRepository())) {
            artifactRepository.deleteFile(oldArtifactId);
        }
        InputStream artifactStream = artifactFile.getInputStream();
        try {
            String artifactFileId = artifactRepository.storeFile(artifactStream);
            artifact.setArtifactName(artifactFile.getOriginalFilename());
            artifact.setArtifactRef(artifactFileId);
            artifact.setArtifactRepository(ArtifactRepositoryConstants.ALIEN_ARTIFACT_REPOSITORY);
            topologyServiceCore.save(topology);
            return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
        } finally {
            Closeables.close(artifactStream, true);
        }
    }

    @ApiOperation(value = "Reset the deployment artifact of the node template.", notes = "The logged-in user must have the application manager role for this application. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/artifacts/{artifactId}/reset", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> resetDeploymentArtifact(@PathVariable String topologyId, @PathVariable String nodeTemplateName,
            @PathVariable String artifactId) throws IOException {

        // Perform check that authorization's ok
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        // Get the node template's artifacts to update
        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);
        NodeTemplate nodeTemplate = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);
        Map<String, DeploymentArtifact> artifacts = nodeTemplate.getArtifacts();
        if (artifacts == null) {
            throw new NotFoundException("Artifact with key [" + artifactId + "] do not exist");
        }
        DeploymentArtifact artifact = artifacts.get(artifactId);
        if (artifact == null) {
            throw new NotFoundException("Artifact with key [" + artifactId + "] do not exist");
        }
        String oldArtifactId = artifact.getArtifactRef();
        if (ArtifactRepositoryConstants.ALIEN_ARTIFACT_REPOSITORY.equals(artifact.getArtifactRepository()) && StringUtils.isNotBlank(oldArtifactId)) {
            artifactRepository.deleteFile(oldArtifactId);
        }

        // get information from the nodetype
        IndexedNodeType indexedNodeType = csarRepoSearch.getElementInDependencies(IndexedNodeType.class, nodeTemplate.getType(), topology.getDependencies());
        DeploymentArtifact baseArtifact = indexedNodeType.getArtifacts().get(artifactId);

        if (baseArtifact != null) {
            artifact.setArtifactRepository(null);
            artifact.setArtifactRef(baseArtifact.getArtifactRef());
            artifact.setArtifactName(baseArtifact.getArtifactName());
            topologyServiceCore.save(topology);
        } else {
            log.warn("Reset service for the artifact <" + artifactId + "> on the node template <" + nodeTemplateName + "> failed.");
        }
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    /**
     * Update application's input artifact.
     *
     * @param topologyId The topology's id
     * @param inputArtifactId artifact's id
     * @return nothing if success, error will be handled in global exception strategy
     * @throws IOException
     */
    @ApiOperation(value = "Updates the deployment artifact of the node template.", notes = "The logged-in user must have the application manager role for this application. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/inputArtifacts/{inputArtifactId}/upload", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> updateDeploymentInputArtifact(@PathVariable String topologyId, @PathVariable String inputArtifactId,
            @RequestParam("file") MultipartFile artifactFile) throws IOException {
        // Perform check that authorization's ok
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        // Get the artifact to update
        Map<String, DeploymentArtifact> artifacts = topology.getInputArtifacts();
        if (artifacts == null) {
            throw new NotFoundException("Artifact with key [" + inputArtifactId + "] do not exist");
        }
        DeploymentArtifact artifact = artifacts.get(inputArtifactId);
        if (artifact == null) {
            throw new NotFoundException("Artifact with key [" + inputArtifactId + "] do not exist");
        }
        String oldArtifactId = artifact.getArtifactRef();
        if (ArtifactRepositoryConstants.ALIEN_ARTIFACT_REPOSITORY.equals(artifact.getArtifactRepository())) {
            artifactRepository.deleteFile(oldArtifactId);
        }
        InputStream artifactStream = artifactFile.getInputStream();
        try {
            String artifactFileId = artifactRepository.storeFile(artifactStream);
            artifact.setArtifactName(artifactFile.getOriginalFilename());
            artifact.setArtifactRef(artifactFileId);
            artifact.setArtifactRepository(ArtifactRepositoryConstants.ALIEN_ARTIFACT_REPOSITORY);
            topologyServiceCore.save(topology);
            return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
        } finally {
            Closeables.close(artifactStream, true);
        }
    }

    private Map<String, NodeTemplate> removeRelationShipReferences(String nodeTemplateName, Topology topology) {
        Map<String, NodeTemplate> nodeTemplates = topology.getNodeTemplates();
        Map<String, NodeTemplate> impactedNodeTemplates = Maps.newHashMap();
        List<String> keysToRemove = Lists.newArrayList();
        for (String key : nodeTemplates.keySet()) {
            NodeTemplate nodeTemp = nodeTemplates.get(key);
            if (nodeTemp.getRelationships() == null) {
                continue;
            }
            keysToRemove.clear();
            for (String key2 : nodeTemp.getRelationships().keySet()) {
                RelationshipTemplate relTemp = nodeTemp.getRelationships().get(key2);
                if (relTemp == null) {
                    continue;
                }
                if (relTemp.getTarget() != null && relTemp.getTarget().equals(nodeTemplateName)) {
                    keysToRemove.add(key2);
                }
            }
            for (String relName : keysToRemove) {
                nodeTemplates.get(key).getRelationships().remove(relName);
                impactedNodeTemplates.put(key, nodeTemplates.get(key));
            }
        }
        return impactedNodeTemplates.isEmpty() ? null : impactedNodeTemplates;
    }

    private IndexedNodeType findIndexedNodeType(final String indexedNodeTypeId) {
        IndexedNodeType indexedNodeType = alienDAO.findById(IndexedNodeType.class, indexedNodeTypeId);
        if (indexedNodeType == null) {
            throw new NotFoundException("Indexed Node Type [" + indexedNodeTypeId + "] cannot be found");
        }
        return indexedNodeType;
    }

    /**
     * Delete a {@link RelationshipTemplate} from a {@link NodeTemplate} in a {@link Topology}.
     *
     * @param topologyId The id of the topology in which the node template lies.
     * @param nodeTemplateName The name of the node template from which we should delete the relationship.
     * @param relationshipName The name of the relationship to delete.
     * @return A rest response with no errors if successful.
     */
    @ApiOperation(value = "Delete a relationship from a node template.", notes = "Returns a response with no errors in case of success. Application role required [ APPLICATION_MANAGER | APPLICATION_DEVOPS ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/relationships/{relationshipName}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> deleteRelationshipTemplate(@PathVariable String topologyId, @PathVariable String nodeTemplateName,
            @PathVariable String relationshipName) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);

        NodeTemplate template = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);
        log.debug("Removing the Relationship template <" + relationshipName + "> from the Node template <" + nodeTemplateName + ">, Topology <"
                + topology.getId() + "> .");
        RelationshipTemplate relationshipTemplate = template.getRelationships().get(relationshipName);
        if (relationshipTemplate != null) {
            topologyService.unloadType(topology, relationshipTemplate.getType());
            template.getRelationships().remove(relationshipName);
        } else {
            throw new NotFoundException("The relationship with name [" + relationshipName + "] do not exist for the node [" + nodeTemplateName
                    + "] of the topology [" + topologyId + "]");
        }
        workflowBuilderService.removeRelationship(topology, nodeTemplateName, relationshipName, relationshipTemplate);
        topologyServiceCore.save(topology);
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    @ApiOperation(value = "Associate an artifact to an input artifact (create it if it doesn't exist).", notes = "Returns a response with no errors and no data in success case. Application role required [ APPLICATION_MANAGER | ARCHITECT ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/artifacts/{artifactId}/{inputArtifactId}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> setInputArtifact(@PathVariable String topologyId, @PathVariable String nodeTemplateName, @PathVariable String artifactId,
            @PathVariable String inputArtifactId) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);
        NodeTemplate nodeTemplate = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);

        if (nodeTemplate.getArtifacts() != null && nodeTemplate.getArtifacts().containsKey(artifactId)) {
            DeploymentArtifact nodeArtifact = nodeTemplate.getArtifacts().get(artifactId);
            if (topology.getInputArtifacts() != null && topology.getInputArtifacts().containsKey(inputArtifactId)) {
                // the input artifact already exist
            } else {
                // we create the input artifact
                DeploymentArtifact inputArtifact = new DeploymentArtifact();
                inputArtifact.setArchiveName(nodeArtifact.getArchiveName());
                inputArtifact.setArchiveVersion(nodeArtifact.getArchiveVersion());
                inputArtifact.setArtifactType(nodeArtifact.getArtifactType());
                Map<String, DeploymentArtifact> inputArtifacts = topology.getInputArtifacts();
                if (inputArtifacts == null) {
                    inputArtifacts = Maps.newHashMap();
                    topology.setInputArtifacts(inputArtifacts);
                }
                inputArtifacts.put(inputArtifactId, inputArtifact);
            }
            InputArtifactUtil.setInputArtifact(nodeArtifact, inputArtifactId);
        } else {
            // attributeName does not exists in the node template
            return RestResponseBuilder.<TopologyDTO> builder().error(RestErrorBuilder.builder(RestErrorCode.PROPERTY_MISSING_ERROR).build()).build();
        }
        topologyServiceCore.save(topology);
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    @ApiOperation(value = "Un-associate an artifact from the input artifact.", notes = "Returns a response with no errors and no data in success case. Application role required [ APPLICATION_MANAGER | ARCHITECT ]")
    @RequestMapping(value = "/{topologyId:.+}/nodetemplates/{nodeTemplateName}/artifacts/{artifactId}/{inputArtifactId}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> unsetInputArtifact(@PathVariable String topologyId, @PathVariable String nodeTemplateName, @PathVariable String artifactId,
            @PathVariable String inputArtifactId) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        Map<String, NodeTemplate> nodeTemplates = TopologyServiceCore.getNodeTemplates(topology);
        NodeTemplate nodeTemplate = TopologyServiceCore.getNodeTemplate(topologyId, nodeTemplateName, nodeTemplates);
        if (nodeTemplate.getArtifacts() != null && nodeTemplate.getArtifacts().containsKey(artifactId)) {
            InputArtifactUtil.unsetInputArtifact(nodeTemplate.getArtifacts().get(artifactId));
        }
        topologyServiceCore.save(topology);
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    @ApiOperation(value = "Rename input artifact id.", notes = "Returns a response with no errors and no data in success case. Application role required [ APPLICATION_MANAGER | ARCHITECT ]")
    @RequestMapping(value = "/{topologyId:.+}/inputArtifacts/{inputArtifactId}", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> updateInputArtifactId(@PathVariable final String topologyId, @PathVariable final String inputArtifactId,
            @RequestParam final String newId) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        if (topology.getInputArtifacts().containsKey(newId)) {
            // TODO: throw an exception
        }
        DeploymentArtifact inputArtifact = topology.getInputArtifacts().remove(inputArtifactId);
        if (inputArtifact != null) {
            topology.getInputArtifacts().put(newId, inputArtifact);

            // change the value of concerned node template artifacts
            for (NodeTemplate nodeTemplate : topology.getNodeTemplates().values()) {
                if (nodeTemplate.getArtifacts() != null) {
                    for (DeploymentArtifact dArtifact : nodeTemplate.getArtifacts().values()) {
                        InputArtifactUtil.updateInputArtifactIdIfNeeded(dArtifact, inputArtifactId, newId);
                    }
                }
            }

        }

        topologyServiceCore.save(topology);
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    @ApiOperation(value = "Un-associate an artifact from the input artifact.", notes = "Returns a response with no errors and no data in success case. Application role required [ APPLICATION_MANAGER | ARCHITECT ]")
    @RequestMapping(value = "/{topologyId:.+}/inputArtifacts/{inputArtifactId}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<TopologyDTO> deleteInputArtifact(@PathVariable final String topologyId, @PathVariable final String inputArtifactId) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        topologyService.checkEditionAuthorizations(topology);
        topologyService.throwsErrorIfReleased(topology);

        DeploymentArtifact inputArtifact = topology.getInputArtifacts().remove(inputArtifactId);
        if (inputArtifact != null) {

            // change the value of concerned node template artifacts
            for (NodeTemplate nodeTemplate : topology.getNodeTemplates().values()) {
                if (nodeTemplate.getArtifacts() != null) {
                    for (DeploymentArtifact dArtifact : nodeTemplate.getArtifacts().values()) {
                        if (inputArtifactId.equals(InputArtifactUtil.getInputArtifactId(dArtifact))) {
                            InputArtifactUtil.unsetInputArtifact(dArtifact);
                        }
                    }
                }
            }

        }

        topologyServiceCore.save(topology);
        return RestResponseBuilder.<TopologyDTO> builder().data(topologyService.buildTopologyDTO(topology)).build();
    }

    @ApiOperation(value = "Get the version of application or topology related to this topology.")
    @RequestMapping(value = "/{topologyId:.+}/version", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public RestResponse<AbstractTopologyVersion> getVersion(@PathVariable String topologyId) {
        Topology topology = topologyServiceCore.getOrFail(topologyId);
        if (topology == null) {
            throw new NotFoundException("No topology found for " + topologyId);
        }
        AbstractTopologyVersion version = null;
        if (topology.getDelegateType().equalsIgnoreCase(TopologyTemplate.class.getSimpleName())) {
            version = topologyTemplateVersionService.getByTopologyId(topologyId);
        } else {
            version = applicationVersionService.getByTopologyId(topologyId);
        }
        if (version == null) {
            throw new NotFoundException("No version found for topology " + topologyId);
        }
        return RestResponseBuilder.<AbstractTopologyVersion> builder().data(version).build();
    }
}
