package alien4cloud.model.service;

import java.util.Date;

import org.alien4cloud.tosca.model.instances.NodeInstance;
import org.elasticsearch.annotation.DateField;
import org.elasticsearch.annotation.ESObject;
import org.elasticsearch.annotation.Id;
import org.elasticsearch.annotation.ObjectField;
import org.elasticsearch.annotation.StringField;
import org.elasticsearch.annotation.query.TermFilter;
import org.elasticsearch.mapping.IndexType;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import alien4cloud.model.common.IDatableResource;
import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.security.AbstractSecurityEnabledResource;
import alien4cloud.utils.version.Version;
import io.swagger.annotations.ApiModel;
import lombok.Getter;
import lombok.Setter;

/**
 * A service is something running somewhere, exposing capabilities and requirements, matchable in a topology in place of an abstract component.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@ESObject
@ApiModel(value = "Service.", description = "A service is something running somewhere, exposing capabilities and requirements, matchable in a topology in place of an abstract component.")
public class ServiceResource extends AbstractSecurityEnabledResource implements IDatableResource {
    @Id
    private String id;

    @TermFilter
    @StringField(indexType = IndexType.not_analyzed)
    private String name;

    @TermFilter
    @StringField(indexType = IndexType.not_analyzed, includeInAll = false)
    private String version;

    // FIXME no need to serialize to REST.
    @ObjectField
    @TermFilter(paths = { "majorVersion", "minorVersion", "incrementalVersion", "buildNumber", "qualifier" })
    private Version nestedVersion;

    @StringField(indexType = IndexType.no)
    private String description;

    @ObjectField
    private NodeInstance nodeInstance;

    /** Id of the locations that are authorized to match this service. */
    @TermFilter
    @StringField(indexType = IndexType.not_analyzed, includeInAll = false)
    private String[] locationIds;

    /** If the service is managed by an application. Defines the environment for which the service is managed. */
    @TermFilter
    @StringField(indexType = IndexType.not_analyzed, includeInAll = false)
    private String environmentId;

    /** The id of the current associated deployment. */
    @TermFilter
    @StringField(indexType = IndexType.not_analyzed, includeInAll = false)
    private String deploymentId;

    @DateField(index = IndexType.no, includeInAll = false)
    private Date creationDate;

    @DateField(index = IndexType.no, includeInAll = false)
    private Date lastUpdateDate;

    public void start() {
        setState(ToscaNodeLifecycleConstants.STARTED);
    }

    public void stop() {
        setState(ToscaNodeLifecycleConstants.STOPPED);
    }

    @JsonIgnore
    public void setState(String state) {
        nodeInstance.setAttribute(ToscaNodeLifecycleConstants.ATT_STATE, state);
    }

    @JsonProperty
    public String getState() {
        return nodeInstance.getAttributeValues().get(ToscaNodeLifecycleConstants.ATT_STATE);
    }

}
