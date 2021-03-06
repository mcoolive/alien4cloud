define(function (require) {
  'use strict';

  var modules = require('modules');
  var states = require('states');
  var _ = require('lodash');
  var angular = require('angular');

  require('scripts/common/directives/facet_search_panel');
  require('scripts/common/directives/pagination');
  require('scripts/components/services/repository');
  require('scripts/common/directives/generic_form');

  states.state('components.repositories', {
    url: '/repositories',
    template: '<ui-view/>',
    controller: 'LayoutCtrl',
    menu: {
      id: 'cm.components.repositories.list',
      state: 'components.repositories.list',
      key: 'NAVBAR.MENU_REPOSITORIES',
      icon: 'fa fa-github',
      priority: 20,
      roles: ['COMPONENTS_MANAGER']
    }
  });
  states.state('components.repositories.list', {
    url: '/list',
    templateUrl: 'views/components/repository_list.html',
    controller: 'RepositoryListCtrl'
  });
  states.forward('components.repositories', 'components.repositories.list');

  var NewRepositoryCtrl = ['$scope', '$uibModalInstance', 'repositoryPlugins', 'repositoryService', 'repositoryPluginConfigurationService',
    function ($scope, $uibModalInstance, repositoryPlugins, repositoryService, repositoryPluginConfigurationService) {

      $scope.repositoryPlugins = repositoryPlugins;

      $scope.repository = {configuration: {}};

      $scope.repositoryTypes = _.map($scope.repositoryPlugins, 'repositoryType');

      $scope.repository.type = $scope.repositoryTypes.length > 0 ? $scope.repositoryTypes[0] : undefined;

      $scope.step = 1;

      $scope.maxStep = 2;

      $scope.cancel = function () {
        $uibModalInstance.dismiss('cancel');
      };

      var findPluginId = function (repositoryType) {
        return _.find($scope.repositoryPlugins, function (plugin) {
          return plugin.repositoryType === repositoryType;
        }).pluginComponent.pluginId;
      };

      $scope.prepareStep = function (forStep) {
        switch (forStep) {
          case 2:
            $scope.repository.pluginId = findPluginId($scope.repository.type);
            repositoryPluginConfigurationService.get({
              pluginId: $scope.repository.pluginId
            }, undefined, function (response) {
              $scope.configurationDefinition = response.data;
            });
        }
      };

      var closeModal = function () {
        $uibModalInstance.close($scope.repository);
      };

      $scope.next = function (valid) {
        if (valid) {
          $scope.prepareStep(++$scope.step);
          if ($scope.step > $scope.maxStep) {
            // create a new application from the given name and description.
            return repositoryService.create({}, angular.toJson($scope.repository), closeModal, closeModal).$promise;
          }
        }
      };
    }];

  var UpdateRepositoryConfigurationController = ['$scope', '$uibModalInstance', 'repositoryService', 'configurationDefinition', 'repository',
    function ($scope, $uibModalInstance, repositoryService, configurationDefinition, repository) {

      $scope.configurationDefinition = configurationDefinition;
      $scope.repository = repository;

      var closeModal = function () {
        $uibModalInstance.close();
      };

      $scope.save = function () {
        return repositoryService.update({repositoryId: $scope.repository.id}, angular.toJson({configuration: $scope.repository.configuration}), closeModal, closeModal).$promise;
      };

      $scope.cancel = function () {
        $uibModalInstance.dismiss('cancel');
      };
    }
  ];

  modules.get('a4c-components', ['ui.router', 'ui.bootstrap']).controller('RepositoryListCtrl', ['$scope', '$uibModal', 'repositoryService', '$window', '$filter',
    function ($scope, $uibModal, repositoryService, $window, $filter) {

      $scope.onSearch = function (searchConfig) {
        $scope.searchConfig = searchConfig;
      };

      $scope.tableMaxHeight = window.innerHeight - 190;
      angular.element($window).bind('resize', function () {
        $scope.tableMaxHeight = window.innerHeight - 190;
        $scope.$digest();
      });

      $scope.deleteRepository = function (repository) {
        repositoryService.remove({
          repositoryId: repository.id
        }, undefined, function () {
          $scope.searchConfig.service.search();
        });
      };

      $scope.openCreateRepositoryModal = function () {
        var modalInstance = $uibModal.open({
          templateUrl: 'views/components/repository_new.html',
          controller: NewRepositoryCtrl,
          resolve: {
            repositoryPlugins: ['repositoryPluginService', function (repositoryPluginService) {
              return repositoryPluginService.get({}, undefined).$promise.then(function (response) {
                return response.data;
              });
            }]
          }
        });
        modalInstance.result.then(function () {
          $scope.searchConfig.service.search();
        });
      };

      $scope.openUpdateRepositoryConfigurationModal = function (repository) {
        $uibModal.open({
          templateUrl: 'views/components/repository_update.html',
          controller: UpdateRepositoryConfigurationController,
          resolve: {
            configurationDefinition: ['repositoryPluginConfigurationService', function (repositoryPluginConfigurationService) {
              return repositoryPluginConfigurationService.get({
                pluginId: repository.pluginId
              }, undefined).$promise.then(function (response) {
                return response.data;
              });
            }],
            repository: function () {
              return repository;
            }
          }
        });
      };

      $scope.updateRepository = function (repository, field, newValue) {
        var request = {};
        request[field] = newValue;
        return repositoryService.update({repositoryId: repository.id}, angular.toJson(request), undefined).$promise;
      };

      /** Used to display the correct text in UI */
      $scope.facetIdConverter = {
        toDisplayFacet: function (termId, filterPrefix) {
          return $filter('translate')($filter('uppercase')(filterPrefix + termId));
        }
      };
    }]);
});
