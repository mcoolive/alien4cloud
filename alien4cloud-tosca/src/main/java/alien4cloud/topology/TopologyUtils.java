package alien4cloud.topology;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alien4cloud.tosca.model.definitions.Interface;
import org.alien4cloud.tosca.model.definitions.Operation;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeGroup;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.ScalingPolicy;
import org.alien4cloud.tosca.model.templates.SubstitutionTarget;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.NodeType;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.Node;

import com.google.common.collect.Maps;

import alien4cloud.exception.NotFoundException;
import alien4cloud.tosca.ToscaNormativeUtil;
import alien4cloud.tosca.normative.NormativeComputeConstants;
import alien4cloud.tosca.parser.ParsingError;
import alien4cloud.tosca.parser.ParsingErrorLevel;
import alien4cloud.tosca.parser.impl.ErrorCode;
import alien4cloud.utils.MapUtil;
import alien4cloud.utils.NameValidationUtils;
import alien4cloud.utils.PropertyUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TopologyUtils {

    private TopologyUtils() {
    }

    /**
     * Extract all interfaces that have given operations, and filter out all operations that are not in the includedOperations
     * 
     * @param interfaces interfaces to filter
     * @param includedOperations operations that will be included in the result
     * @return filter interfaces
     */
    public static Map<String, Interface> filterInterfaces(Map<String, Interface> interfaces, Set<String> includedOperations) {
        Map<String, Interface> result = Maps.newHashMap();
        for (Map.Entry<String, Interface> interfaceEntry : interfaces.entrySet()) {
            Map<String, Operation> operations = Maps.newHashMap();
            for (Map.Entry<String, Operation> operationEntry : interfaceEntry.getValue().getOperations().entrySet()) {
                if (includedOperations.contains(operationEntry.getKey())) {
                    operations.put(operationEntry.getKey(), operationEntry.getValue());
                }
            }
            if (!operations.isEmpty()) {
                Interface inter = new Interface();
                inter.setDescription(interfaceEntry.getValue().getDescription());
                inter.setOperations(operations);
                result.put(interfaceEntry.getKey(), inter);
            }
        }
        return result;
    }

    /**
     * Extract interfaces that have implemented operations only.
     *
     * @param allInterfaces all interfaces
     * @return interfaces that have implemented operations
     */
    public static Map<String, Interface> filterAbstractInterfaces(Map<String, Interface> allInterfaces) {
        Map<String, Interface> interfaces = Maps.newHashMap();
        for (Map.Entry<String, Interface> interfaceEntry : allInterfaces.entrySet()) {
            Map<String, Operation> operations = Maps.newHashMap();
            for (Map.Entry<String, Operation> operationEntry : interfaceEntry.getValue().getOperations().entrySet()) {
                if (operationEntry.getValue().getImplementationArtifact() == null) {
                    // Don't consider operation which do not have any implementation artifact
                    continue;
                }
                operations.put(operationEntry.getKey(), operationEntry.getValue());
            }
            if (!operations.isEmpty()) {
                // At least one operation fulfill the criteria
                Interface inter = new Interface();
                inter.setDescription(interfaceEntry.getValue().getDescription());
                inter.setOperations(operations);
                interfaces.put(interfaceEntry.getKey(), inter);
            }
        }
        return interfaces;
    }

    public static void setNullScalingPolicy(NodeTemplate nodeTemplate, NodeType resourceType) {
        // FIXME Workaround to remove default scalable properties from compute
        if (ToscaNormativeUtil.isFromType(NormativeComputeConstants.COMPUTE_TYPE, resourceType)) {
            if (nodeTemplate.getCapabilities() != null) {
                Capability scalableCapability = nodeTemplate.getCapabilities().get(NormativeComputeConstants.SCALABLE);
                if (scalableCapability != null && scalableCapability.getProperties() != null) {
                    scalableCapability.getProperties().put(NormativeComputeConstants.SCALABLE_MIN_INSTANCES, null);
                    scalableCapability.getProperties().put(NormativeComputeConstants.SCALABLE_MAX_INSTANCES, null);
                    scalableCapability.getProperties().put(NormativeComputeConstants.SCALABLE_DEFAULT_INSTANCES, null);
                }
            }
        }
    }

    public static ScalingPolicy getScalingPolicy(Capability capability) {
        int initialInstances = getScalingProperty(NormativeComputeConstants.SCALABLE_DEFAULT_INSTANCES, capability);
        int minInstances = getScalingProperty(NormativeComputeConstants.SCALABLE_MIN_INSTANCES, capability);
        int maxInstances = getScalingProperty(NormativeComputeConstants.SCALABLE_MAX_INSTANCES, capability);
        return new ScalingPolicy(minInstances, maxInstances, initialInstances);
    }

    public static int getScalingProperty(String propertyName, Capability capability) {
        if (MapUtils.isEmpty(capability.getProperties())) {
            throw new NotFoundException("The capability scalable has no defined properties, verify your tosca-normative-type archive");
        }

        if (!capability.getProperties().containsKey(propertyName)) {
            throw new NotFoundException(propertyName + " property is not found in the the capability");
        }

        // default value is 1
        int propertyValue = 1;

        String rawPropertyValue = PropertyUtil.getScalarValue(capability.getProperties().get(propertyName));
        if (StringUtils.isNotBlank(rawPropertyValue)) {
            propertyValue = Integer.parseInt(rawPropertyValue);
        }
        return propertyValue;
    }

    public static void setScalingProperty(String propertyName, int propertyValue, Capability capability) {
        if (MapUtils.isEmpty(capability.getProperties())) {
            throw new NotFoundException("The capability scalable has no defined properties, verify your tosca-normative-type archive");
        }
        capability.getProperties().put(propertyName, new ScalarPropertyValue(String.valueOf(propertyValue)));
    }

    private static Capability getCapability(Topology topology, String nodeTemplateId, String capabilityName, boolean throwNotFoundException) {
        return getCapability(topology.getNodeTemplates(), nodeTemplateId, capabilityName, throwNotFoundException);
    }

    private static Capability getCapability(Map<String, NodeTemplate> nodes, String nodeTemplateId, String capabilityName, boolean throwNotFoundException) {
        NodeTemplate node = nodes.get(nodeTemplateId);
        if (node == null) {
            if (throwNotFoundException) {
                throw new NotFoundException("Node " + nodeTemplateId + " is not found in the topology");
            } else {
                return null;
            }
        }
        Map<String, Capability> capabilities = node.getCapabilities();
        if (MapUtils.isEmpty(capabilities)) {
            if (throwNotFoundException) {
                throw new NotFoundException("Node " + nodeTemplateId + " does not have any capability");
            } else {
                return null;
            }
        }
        Capability capability = node.getCapabilities().get(capabilityName);
        if (capability == null) {
            if (throwNotFoundException) {
                throw new NotFoundException("Node " + nodeTemplateId + " does not have the capability scalable");
            } else {
                return null;
            }
        }
        return capability;
    }

    public static Capability getScalableCapability(Topology topology, String nodeTemplateId, boolean throwNotFoundException) {
        return getCapability(topology, nodeTemplateId, NormativeComputeConstants.SCALABLE, throwNotFoundException);
    }

    public static Capability getScalableCapability(Map<String, NodeTemplate> nodes, String nodeTemplateId, boolean throwNotFoundException) {
        return getCapability(nodes, nodeTemplateId, NormativeComputeConstants.SCALABLE, throwNotFoundException);
    }

    public static int getAvailableGroupIndex(Topology topology) {
        if (topology == null || topology.getGroups() == null) {
            return 0;
        }
        Collection<NodeGroup> nodeGroups = topology.getGroups().values();
        LinkedHashSet<Integer> indexSet = new LinkedHashSet<>(nodeGroups.size());
        for (int i = 0; i < nodeGroups.size(); i++) {
            indexSet.add(i);
        }
        for (NodeGroup nodeGroup : nodeGroups) {
            indexSet.remove(nodeGroup.getIndex());
        }
        if (indexSet.isEmpty()) {
            return nodeGroups.size();
        }
        return indexSet.iterator().next();
    }

    private static String toLowerCase(String text) {
        return text.substring(0, 1).toLowerCase() + text.substring(1);
    }

    private static String toUpperCase(String text) {
        return text.substring(0, 1).toUpperCase() + text.substring(1);
    }

    /**
     * Construct a relationship name from target and relationship type.
     *
     * @param type type of the relationship
     * @param targetName name of the target
     * @return the default constructed name
     */
    private static String getRelationShipName(String type, String targetName) {
        String[] tokens = type.split("\\.");
        if (tokens.length > 1) {
            return toLowerCase(tokens[tokens.length - 1]) + toUpperCase(targetName);
        } else {
            return toLowerCase(type) + toUpperCase(targetName);
        }
    }

    /**
     * Update properties in a topology
     */
    private static void updateOnNodeTemplateNameChange(String oldNodeTemplateName, String newNodeTemplateName, Topology topology) {
        // Output properties
        if (topology.getOutputProperties() != null) {
            Set<String> oldPropertiesOutputs = topology.getOutputProperties().remove(oldNodeTemplateName);
            if (oldPropertiesOutputs != null) {
                topology.getOutputProperties().put(newNodeTemplateName, oldPropertiesOutputs);
            }
        }
        // substitution mapping
        if (topology.getSubstitutionMapping() != null) {
            if (topology.getSubstitutionMapping().getCapabilities() != null) {
                for (SubstitutionTarget st : topology.getSubstitutionMapping().getCapabilities().values()) {
                    if (st.getNodeTemplateName().equals(oldNodeTemplateName)) {
                        st.setNodeTemplateName(newNodeTemplateName);
                    }
                }
            }
            if (topology.getSubstitutionMapping().getRequirements() != null) {
                for (SubstitutionTarget st : topology.getSubstitutionMapping().getRequirements().values()) {
                    if (st.getNodeTemplateName().equals(oldNodeTemplateName)) {
                        st.setNodeTemplateName(newNodeTemplateName);
                    }
                }
            }
        }
    }

    /**
     * <p>
     * Update the name of a node template in the relationships of a topology.
     * This requires two operations:
     * <ul>
     * <li>Rename the target node of a relationship</li>
     * <li>If a relationship has an auto-generated id, update it's id to take in account the new target name.</li>
     * </ul>
     * </p>
     *
     * @param oldNodeTemplateName Name of the node template that changes.
     * @param newNodeTemplateName New name for the node template.
     * @param nodeTemplates Map of all node templates in the topology.
     */
    public static void refreshNodeTempNameInRelationships(String oldNodeTemplateName, String newNodeTemplateName, Map<String, NodeTemplate> nodeTemplates) {
        // node templates copy
        for (NodeTemplate nodeTemplate : nodeTemplates.values()) {
            if (nodeTemplate.getRelationships() != null) {
                refreshNodeTemplateNameInRelationships(oldNodeTemplateName, newNodeTemplateName, nodeTemplate.getRelationships());
            }
        }
    }

    private static void refreshNodeTemplateNameInRelationships(String oldNodeTemplateName, String newNodeTemplateName,
            Map<String, RelationshipTemplate> relationshipTemplates) {
        Map<String, String> updatedKeys = Maps.newHashMap();
        for (Map.Entry<String, RelationshipTemplate> relationshipTemplateEntry : relationshipTemplates.entrySet()) {
            String relationshipTemplateId = relationshipTemplateEntry.getKey();
            RelationshipTemplate relationshipTemplate = relationshipTemplateEntry.getValue();

            if (relationshipTemplate.getTarget().equals(oldNodeTemplateName)) {
                relationshipTemplate.setTarget(newNodeTemplateName);
                String formatedOldNodeName = getRelationShipName(relationshipTemplate.getType(), oldNodeTemplateName);
                // if the id/name of the relationship is auto-generated we should update it also as auto-generation is <typeName+targetId>
                if (relationshipTemplateId.equals(formatedOldNodeName)) {
                    String newRelationshipTemplateId = getRelationShipName(relationshipTemplate.getType(), newNodeTemplateName);
                    // check that the new name is not already used (so we won't override another relationship)...
                    String validNewRelationshipTemplateId = newRelationshipTemplateId;
                    int counter = 0;
                    while (relationshipTemplates.containsKey(validNewRelationshipTemplateId)) {
                        validNewRelationshipTemplateId = newRelationshipTemplateId + counter;
                        counter++;
                    }
                    updatedKeys.put(relationshipTemplateId, validNewRelationshipTemplateId);
                }
            }
        }

        // update the relationship keys if any has been impacted
        for (Map.Entry<String, String> updateKeyEntry : updatedKeys.entrySet()) {
            RelationshipTemplate relationshipTemplate = relationshipTemplates.remove(updateKeyEntry.getKey());
            relationshipTemplates.put(updateKeyEntry.getValue(), relationshipTemplate);
        }
    }

    /**
     * Manage node group members when a node name is removed or its name has changed.
     *
     * @param newName : the new name of the node or <code>null</code> if the node has been removed.
     */
    public static void updateGroupMembers(Topology topology, NodeTemplate template, String nodeName, String newName) {
        Map<String, NodeGroup> topologyGroups = topology.getGroups();
        if (template.getGroups() != null && !template.getGroups().isEmpty() && topologyGroups != null) {
            for (String groupId : template.getGroups()) {
                NodeGroup nodeGroup = topologyGroups.get(groupId);
                if (nodeGroup != null && nodeGroup.getMembers() != null) {
                    boolean removed = nodeGroup.getMembers().remove(nodeName);
                    if (removed && newName != null) {
                        nodeGroup.getMembers().add(newName);
                    }
                }
            }
        }
    }

    /**
     * Rename formattedOldNodeName node template of a topology.
     * 
     * @param topology
     * @param nodeTemplateName
     * @param newNodeTemplateName
     */
    public static void renameNodeTemplate(Topology topology, String nodeTemplateName, String newNodeTemplateName) {
        Map<String, NodeTemplate> nodeTemplates = getNodeTemplates(topology);
        NodeTemplate nodeTemplate = getNodeTemplate(topology.getId(), nodeTemplateName, nodeTemplates);

        nodeTemplate.setName(newNodeTemplateName);
        nodeTemplates.put(newNodeTemplateName, nodeTemplate);
        nodeTemplates.remove(nodeTemplateName);
        refreshNodeTempNameInRelationships(nodeTemplateName, newNodeTemplateName, nodeTemplates);
        updateOnNodeTemplateNameChange(nodeTemplateName, newNodeTemplateName, topology);
        updateGroupMembers(topology, nodeTemplate, nodeTemplateName, newNodeTemplateName);
    }


    /**
     * In alien 4 Cloud we try
     * Rename the node template with an invalid name on the topology.
     * 
     * @param topology
     * @param parsingErrors
     * @param objectToNodeMap
     */
    public static void normalizeAllNodeTemplateName(Topology topology, List<ParsingError> parsingErrors, Map<Object, Node> objectToNodeMap) {
        if (topology.getNodeTemplates() != null && !topology.getNodeTemplates().isEmpty()) {
            Map<String, NodeTemplate> nodeTemplates = Maps.newHashMap(topology.getNodeTemplates());
            for (Map.Entry<String, NodeTemplate> nodeEntry : nodeTemplates.entrySet()) {
                String nodeName = nodeEntry.getKey();
                if (!NameValidationUtils.isValid(nodeName)) {
                    String newName = StringUtils.stripAccents(nodeName);
                    newName = NameValidationUtils.DEFAULT_NAME_REPLACE_PATTERN.matcher(newName).replaceAll("_");
                    if (topology.getNodeTemplates().containsKey(newName)) {
                        int i = 1;
                        while (topology.getNodeTemplates().containsKey(newName + i)) {
                            i++;
                        }
                        newName = newName + i;
                    }
                    renameNodeTemplate(topology, nodeName, newName);
                    if (parsingErrors != null) {
                        Node node = (Node) MapUtil.get(objectToNodeMap, nodeName);
                        Mark startMark = null;
                        Mark endMark = null;
                        if (node != null) {
                            startMark = node.getStartMark();
                            endMark = node.getEndMark();
                        }
                        parsingErrors.add(
                                new ParsingError(ParsingErrorLevel.WARNING, ErrorCode.INVALID_NAME, "NodeTemplate", startMark, nodeName, endMark, newName));
                    }
                }
            }
        }
    }

    /**
     * Get the Map of {@link NodeTemplate} from a topology
     *
     * @param topology the topology
     * @return this topology's node templates
     */
    public static Map<String, NodeTemplate> getNodeTemplates(Topology topology) {
        Map<String, NodeTemplate> nodeTemplates = topology.getNodeTemplates();
        if (nodeTemplates == null) {
            throw new NotFoundException("Topology [" + topology.getId() + "] do not have any node template");
        }
        return nodeTemplates;
    }

    /**
     * Get a {@link NodeTemplate} given its name from a map
     *
     * @param topologyId the topology's id
     * @param nodeTemplateName the name of the node template
     * @param nodeTemplates the topology's node templates
     * @return the found node template, throws NotFoundException if not found
     */
    public static NodeTemplate getNodeTemplate(String topologyId, String nodeTemplateName, Map<String, NodeTemplate> nodeTemplates) {
        NodeTemplate nodeTemplate = nodeTemplates.get(nodeTemplateName);
        if (nodeTemplate == null) {
            throw new NotFoundException("Topology [" + topologyId + "] do not have node template with name [" + nodeTemplateName + "]");
        }
        return nodeTemplate;
    }

    /**
     * Get a {@link NodeTemplate} given its name from a topology
     *
     * @param topology the topology
     * @param nodeTemplateId the name of the node template
     * @return the found node template, throws NotFoundException if not found
     */
    public static NodeTemplate getNodeTemplate(Topology topology, String nodeTemplateId) {
        Map<String, NodeTemplate> nodeTemplates = getNodeTemplates(topology);
        return getNodeTemplate(topology.getId(), nodeTemplateId, nodeTemplates);
    }
}
