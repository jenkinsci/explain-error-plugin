/**
 * Pipeline Graph View integration for the Explain Error plugin.
 *
 * Injects an "Explain Error" button into the Pipeline Graph View page
 * and monitors node selection to enable/disable it based on whether
 * the selected node represents a failed step.
 */
(function () {
  'use strict';

  document.addEventListener('DOMContentLoaded', function () {
    var container = document.getElementById('explain-error-graph-container');
    if (!container) {
      return;
    }
    initGraphViewExplain(container);
  });

  function initGraphViewExplain(container) {
    checkGraphBuildStatus(function (buildingStatus) {
      // Status 0 = SUCCESS, 1 = RUNNING, 2 = FAILURE/UNSTABLE
      if (buildingStatus === 2) {
        // Build completed with failure — inject button and start monitoring
        injectExplainButton();
        startNodeSelectionMonitor();
      } else if (buildingStatus === 1) {
        // Still running — retry after a delay
        setTimeout(function () {
          initGraphViewExplain(container);
        }, 5000);
      }
      // Status 0 (SUCCESS) — do nothing
    });
  }

  // ---- Build status check ----

  function checkGraphBuildStatus(callback) {
    var container = document.getElementById('explain-error-graph-container');
    var basePath = container.dataset.runUrl;
    var rootURL = document.head.getAttribute('data-rooturl');
    var url = rootURL + '/' + basePath + 'graph-explain-error/checkBuildStatus';

    var headers = crumb.wrap({
      'Content-Type': 'application/x-www-form-urlencoded'
    });

    fetch(url, {
      method: 'POST',
      headers: headers,
      body: ''
    })
      .then(function (response) { return response.json(); })
      .then(function (data) {
        callback(data.buildingStatus);
      })
      .catch(function (error) {
        console.warn('Error checking build status:', error);
        callback(2); // Assume failed on error
      });
  }

  // ---- Button injection ----

  var explainBtn = null;
  var currentSelectedNode = null;

  function injectExplainButton() {
    if (document.querySelector('.explain-error-graph-btn')) {
      return; // Already injected
    }

    // Try to find the top-right action bar (contains Run, Replay, etc.)
    var actionBar = findActionBar();
    if (!actionBar) {
      setTimeout(injectExplainButton, 1000);
      return;
    }

    var container = document.getElementById('explain-error-graph-container');
    var providerName = container.dataset.providerName || 'Unknown';

    var btn = document.createElement('button');
    btn.textContent = 'Explain Error';
    btn.className = 'jenkins-button explain-error-graph-btn jenkins-hidden';
    btn.setAttribute('type', 'button');
    btn.setAttribute('tooltip', 'Provider: ' + providerName);
    btn.onclick = function () {
      handleExplainClick();
    };

    actionBar.appendChild(btn);
    if (typeof Behaviour !== 'undefined') {
      Behaviour.applySubtree(actionBar, true);
    }
    explainBtn = btn;
  }

  function findActionBar() {
    var selectors = [
      '.jenkins-app-bar__controls',
      '.jenkins-app-bar .jenkins-app-bar__controls',
      '#layout-header .page-header__controls'
    ];
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) {
        return el;
      }
    }
    // Fallback: find or create a container in the app bar
    var appBar = document.querySelector('.jenkins-app-bar');
    if (appBar) {
      var controls = appBar.querySelector('.jenkins-app-bar__controls');
      if (controls) return controls;
      var div = document.createElement('div');
      div.className = 'jenkins-app-bar__controls';
      appBar.appendChild(div);
      return div;
    }
    return null;
  }

  // ---- Node selection monitoring ----

  /**
   * Intercept history.pushState and history.replaceState to detect URL changes
   * that don't fire popstate events (used by Pipeline Graph View for node
   * selection).
   */
  function patchHistory() {
    var pushState = history.pushState;
    var replaceState = history.replaceState;

    function onUrlChange() {
      window.dispatchEvent(new Event('urlchange'));
    }

    history.pushState = function () {
      pushState.apply(history, arguments);
      onUrlChange();
    };
    history.replaceState = function () {
      replaceState.apply(history, arguments);
      onUrlChange();
    };
  }

  function startNodeSelectionMonitor() {
    patchHistory();

    // Check initial selection
    checkCurrentSelection();

    // Listen for browser back/forward
    window.addEventListener('popstate', function () {
      checkCurrentSelection();
    });

    // Listen for history.pushState / replaceState
    window.addEventListener('urlchange', function () {
      checkCurrentSelection();
    });

    // Low-frequency polling fallback (in case other mechanisms change the URL)
    setInterval(function () {
      var params = new URLSearchParams(window.location.search);
      var nodeId = params.get('selected-node');
      if (nodeId !== currentSelectedNode) {
        checkCurrentSelection();
      }
    }, 500);
  }

  function checkCurrentSelection() {
    var params = new URLSearchParams(window.location.search);
    var nodeId = params.get('selected-node');

    if (!nodeId || nodeId === currentSelectedNode) {
      if (!nodeId) {
        hideButton();
        currentSelectedNode = null;
      }
      return;
    }
    currentSelectedNode = nodeId;
    // A previously rendered explanation belongs to the node that was selected before —
    // discard it so a stale explanation is never shown for the newly selected node.
    resetExplanationPanel();
    checkNodeFailedStatus(nodeId);
  }

  function resetExplanationPanel() {
    clearExplanationContent();
    hideExplanationPanel();
    var urlLink = document.getElementById('explain-error-graph-url');
    if (urlLink) {
      urlLink.classList.add('jenkins-hidden');
    }
  }

  function checkNodeFailedStatus(nodeId) {
    var container = document.getElementById('explain-error-graph-container');
    var basePath = container.dataset.runUrl;
    var rootURL = document.head.getAttribute('data-rooturl');
    var url = rootURL + '/' + basePath + 'graph-explain-error/checkNodeStatus';

    var headers = crumb.wrap({
      'Content-Type': 'application/x-www-form-urlencoded'
    });

    fetch(url, {
      method: 'POST',
      headers: headers,
      body: 'nodeId=' + encodeURIComponent(nodeId)
    })
      .then(function (response) { return response.json(); })
      .then(function (data) {
        if (data.isFailed) {
          showButton();
        } else {
          hideButton();
        }
      })
      .catch(function (error) {
        console.warn('Error checking node status:', error);
        hideButton();
      });
  }

  function showButton() {
    if (explainBtn) {
      explainBtn.classList.remove('jenkins-hidden');
    }
  }

  function hideButton() {
    if (explainBtn) {
      explainBtn.classList.add('jenkins-hidden');
    }
  }

  // ---- Explain flow ----

  function handleExplainClick() {
    showExplanationPanel();
  }

  function sendExplainRequest(forceNew) {
    var container = document.getElementById('explain-error-graph-container');
    var basePath = container.dataset.runUrl;
    var rootURL = document.head.getAttribute('data-rooturl');
    var url = rootURL + '/' + basePath + 'graph-explain-error/explainNodeError';
    var nodeId = currentSelectedNode;

    if (!nodeId) {
      if (typeof notificationBar !== 'undefined') {
        notificationBar.show('No failed node selected.', notificationBar.WARNING);
      }
      return;
    }

    var body = 'nodeId=' + encodeURIComponent(nodeId);
    if (forceNew) {
      body += '&forceNew=true';
    }

    var headers = crumb.wrap({
      'Content-Type': 'application/x-www-form-urlencoded'
    });

    showSpinner();

    fetch(url, {
      method: 'POST',
      headers: headers,
      body: body
    })
      .then(function (response) { return response.text(); })
      .then(function (text) {
        var json;
        try {
          json = JSON.parse(text);
        } catch (e) {
          throw new Error('Explain failed: Jenkins returned an invalid response.');
        }

        if (json.status === 'success') {
          showExplanationResult(json.message, json.providerName, json.url);
        } else {
          if (typeof notificationBar !== 'undefined') {
            var level = json.status === 'warning'
              ? notificationBar.WARNING
              : notificationBar.ERROR;
            notificationBar.show(json.message, level);
          }
          hideExplanationPanel();
        }
      })
      .catch(function (error) {
        if (typeof notificationBar !== 'undefined') {
          notificationBar.show('Error: ' + error.message, notificationBar.ERROR);
        }
        hideExplanationPanel();
      });
  }

  // ---- Explanation panel ----

  function ensureExplanationPanel() {
    var panel = document.getElementById('explain-error-graph-panel');
    if (panel) {
      return panel;
    }

    var mainPanel = document.querySelector('#main-panel') || document.body;

    panel = document.createElement('div');
    panel.id = 'explain-error-graph-panel';
    panel.className = 'jenkins-hidden';
    panel.style.margin = '20px';

    var rootURL = document.head.getAttribute('data-rooturl') || '';

    panel.innerHTML =
      '<div class="jenkins-card">' +
      '  <div class="jenkins-card__title">' +
      '    <span id="explain-error-graph-provider">AI Error Explanation</span>' +
      '    <div class="jenkins-card__controls">' +
      '      <a href="#" class="jenkins-card__reveal eep-graph-generate-new" tooltip="Generate new explanation">' +
      '        <img src="' + rootURL + '/plugin/ionicons-api/images/symbol-reload.svg" alt="" style="width:16px;height:16px">' +
      '      </a>' +
      '      <a href="#" class="jenkins-card__reveal eep-graph-close" tooltip="Close">' +
      '        <img src="' + rootURL + '/plugin/ionicons-api/images/symbol-close.svg" alt="" style="width:16px;height:16px">' +
      '      </a>' +
      '    </div>' +
      '  </div>' +
      '  <div class="jenkins-card__content">' +
      '    <div id="explain-error-graph-spinner" class="jenkins-hidden" style="text-align:center;padding:20px">' +
      '      Analyzing error logs...' +
      '    </div>' +
      '    <pre id="explain-error-graph-content" class="jenkins-hidden" style="white-space:pre-wrap;word-wrap:break-word;margin-bottom:0"></pre>' +
      '    <div class="jenkins-!-margin-top-3">' +
      '      <a id="explain-error-graph-url" href="#" class="jenkins-button jenkins-!-destructive-color jenkins-hidden" target="_self">' +
      '        View failure output' +
      '      </a>' +
      '    </div>' +
      '  </div>' +
      '</div>';

    mainPanel.appendChild(panel);

    // Wire up controls
    panel.querySelector('.eep-graph-generate-new').addEventListener('click', function (e) {
      e.preventDefault();
      clearExplanationContent();
      sendExplainRequest(true);
    });
    panel.querySelector('.eep-graph-close').addEventListener('click', function (e) {
      e.preventDefault();
      hideExplanationPanel();
    });

    return panel;
  }

  function showExplanationPanel() {
    var content = document.getElementById('explain-error-graph-content');
    if (!content || !content.textContent) {
      // Nothing rendered yet — fetch from the server, which returns the
      // cached explanation when one exists and generates one otherwise.
      sendExplainRequest();
      return;
    }
    var panel = ensureExplanationPanel();
    panel.classList.remove('jenkins-hidden');
  }

  function hideExplanationPanel() {
    var panel = document.getElementById('explain-error-graph-panel');
    if (panel) {
      panel.classList.add('jenkins-hidden');
    }
  }

  function showSpinner() {
    var panel = ensureExplanationPanel();
    panel.classList.remove('jenkins-hidden');
    document.getElementById('explain-error-graph-spinner').classList.remove('jenkins-hidden');
    document.getElementById('explain-error-graph-content').classList.add('jenkins-hidden');
    document.getElementById('explain-error-graph-url').classList.add('jenkins-hidden');
  }

  function showExplanationResult(message, providerName, url) {
    var panel = ensureExplanationPanel();
    panel.classList.remove('jenkins-hidden');

    var spinner = document.getElementById('explain-error-graph-spinner');
    var content = document.getElementById('explain-error-graph-content');
    var urlLink = document.getElementById('explain-error-graph-url');
    var providerSpan = document.getElementById('explain-error-graph-provider');

    spinner.classList.add('jenkins-hidden');
    content.textContent = message;
    content.classList.remove('jenkins-hidden');

    if (url) {
      urlLink.href = url;
      urlLink.classList.remove('jenkins-hidden');
    }

    if (providerName) {
      providerSpan.textContent = 'AI Error Explanation (' + providerName + ')';
    }

    // Update container cache flag
    var container = document.getElementById('explain-error-graph-container');
    container.dataset.hasExplanation = 'true';
  }

  function clearExplanationContent() {
    var content = document.getElementById('explain-error-graph-content');
    if (content) {
      content.classList.add('jenkins-hidden');
      content.textContent = '';
    }
  }

})();
