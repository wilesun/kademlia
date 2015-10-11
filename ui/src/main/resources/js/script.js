'use strict';

angular.module('ui', [])
        .controller('MainCtrl', function ($scope) {
            $scope.loginUsername = '';
            $scope.loginBootstrap = '';
            $scope.errorMessage = '';

            $scope.workingMessage = '';

            $scope.inMessageRate = 0;
            $scope.outMessageRate = 0;

            $scope.loginAction = function () {
                messageSender.loginAction($scope.loginUsername, $scope.loginBootstrap);
            };

            $scope.logoutAction = function () {
                messageSender.logoutAction();
            };

            $scope.resetDevicesAction = function () {
                messageSender.resetDevicesAction();
            };

            $scope.chooseDevicesAction = function () {
                messageSender.chooseDevicesAction($scope.selectedInputDevice, $scope.selectedOutputDevice);
            };

            $scope.devicesChosenAction = function () {
                messageSender.devicesChosenAction();
            };
        });

window.goToLogin = function (message, reset) {
    var appElement = document.querySelector('[ng-app=ui]');
    var $scope = angular.element(appElement).scope();
    $scope = $scope.$$childHead;

    $scope.$apply(function () {
        $scope.state = 'LOGIN';
        $scope.errorMessage = message;

        if (reset === true) {
            $scope.loginUsername = '';
            $scope.loginBootstrap = '';
        }
    });
};

window.goToUnrecoverableError = function (message) {
    var appElement = document.querySelector('[ng-app=ui]');
    var $scope = angular.element(appElement).scope();
    $scope = $scope.$$childHead;

    $scope.$apply(function () {
        $scope.state = 'UNRECOVERABLE_ERROR';
        $scope.unrecoverableErrorMessage = message;
    });
};

window.goToWorking = function (message) {
    var appElement = document.querySelector('[ng-app=ui]');
    var $scope = angular.element(appElement).scope();
    $scope = $scope.$$childHead;

    $scope.$apply(function () {
        $scope.state = 'WORKING';
        $scope.workingMessage = message;
    });
};

window.goToIdle = function () {
    var appElement = document.querySelector('[ng-app=ui]');
    var $scope = angular.element(appElement).scope();
    $scope = $scope.$$childHead;

    $scope.$apply(function () {
        $scope.state = 'ACTIVE_IDLE';
    });
};

window.showDeviceSelection = function (inputDevices, outputDevices) {
    var appElement = document.querySelector('[ng-app=ui]');
    var $scope = angular.element(appElement).scope();
    $scope = $scope.$$childHead;

    $scope.$apply(function () {
        $scope.inputDevices = inputDevices;
        $scope.outputDevices = outputDevices;
        
        $scope.selectedInputDevice = getFirstKey(inputDevices);
        $scope.selectedOutputDevice = getFirstKey(outputDevices);
        
        if ($scope.selectedInputDevice === undefined || $scope.selectedOutputDevice === undefined) {
            console.error("UNDEFINED DEVICE (" + $scope.selectedInputDevice + ") / (" + $scope.selectedOutputDevice + ")");
        }
        
        $scope.chooseDevicesAction(); // Let Java know that the default values are the ones currently selected
        
        $scope.state = 'DEVICE_SELECTION';
    });
};

// http://stackoverflow.com/questions/909003/javascript-getting-the-first-index-of-an-object
window.getFirstKey = function(obj) {
    for (var i in obj) {
        if (obj.hasOwnProperty(i) && typeof (i) !== 'function') {
            return i;
        }
    }
    
    return undefined;
};

window.printObjectToConsole = function(obj) {
    for (var key in Object.keys(obj)) {
        console.warn(key);
        console.warn(obj[key]);
    }
};