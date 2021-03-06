define(function (require) {
  'use strict';
  
  var modules = require('modules');
  
  modules.get('a4c-orchestrators').factory('locationSecurityService', ['$alresource',
    function ($alresource) {
      
      var users = $alresource('rest/latest/orchestrators/:orchestratorId/locations/:locationId/security/users/:username');
      
      var groups = $alresource('rest/latest/orchestrators/:orchestratorId/locations/:locationId/security/groups/:groupId');

      var applications = $alresource('rest/latest/orchestrators/:orchestratorId/locations/:locationId/security/applications/:applicationId');

      var environmentsPerApplication = $alresource('rest/latest/orchestrators/:orchestratorId/locations/:locationId/security/environmentsPerApplication');

      return {
        'users': users,
        'groups': groups,
        'applications': applications,
        'environmentsPerApplication': environmentsPerApplication
      };
    }]);
});
