/**
* Directive used to manage the edition of the properties of a node template.
* Note this directive is not used in the TOSCA topology editor as the editor provides some specific options related to the topology (input/outputs/)
*/
define(function(require) {
  'use strict';

  var modules = require('modules');

  require('scripts/tosca/controllers/node_template_edit_ctrl');

  modules.get('a4c-tosca').directive('a4cNodeTemplateEdit', function() {
    return {
      templateUrl: 'views/tosca/node_template_edit.html',
      restrict: 'E',
      scope: {
        'nodeTemplate': '=', // This is the actual node tempalte to edit.
        'nodeType': '=', // The type of the node template.
        'nodeCapabilityTypes': '=', // map of capability types
        'dependencies': '=', // dependencies
        'isPropertyEditable': '&', // callback operation that should return true if a property or a capability property can be edited.
        'onPropertyUpdate': '&', // callback operation triggered when a property is actually updated
        'onCapabilityPropertyUpdate': '&' // callback operation triggered when a capability property is actually updated
      },
      link: {}
    };
  });
});
