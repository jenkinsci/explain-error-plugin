let graphBuildStatus = null;

document.addEventListener('DOMContentLoaded', function () {
  installHistoryListener();
  installGraphExplainButton();
  checkGraphBuildStatusAndInstallButton();
  updateGraphExplainButton();
  observeGraphViewChanges();
});

function checkGraphBuildStatusAndInstallButton() {
  checkGraphBuildStatus(function(buildingStatus) {
    if (buildingStatus == null) {
      installGraphExplainButton();
      updateGraphExplainButton();
      return;
    }

    graphBuildStatus = buildingStatus;
    if (buildingStatus == 0) {
      removeGraphExplainButton();
    } else {
      installGraphExplainButton();
      updateGraphExplainButton();
      if (buildingStatus == 1) {
        setTimeout(checkGraphBuildStatusAndInstallButton, 5000);
      }
    }
  });
}

function checkGraphBuildStatus(callback) {
  const container = document.getElementById('explain-error-container');
  if (!container) {
    callback(null);
    return;
  }

  const basePath = container.dataset.runUrl;
  const rootURL = document.head.getAttribute("data-rooturl");
  const url = rootURL + '/' + basePath + 'console-explain-error/checkBuildStatus';
  let headers = {
    "Content-Type": "application/x-www-form-urlencoded",
  };
  if (window.crumb && typeof window.crumb.wrap === 'function') {
    headers = window.crumb.wrap(headers);
  }

  fetch(url, {
    method: "POST",
    headers: headers,
    body: ""
  })
  .then(response => response.json())
  .then(data => {
    callback(data.buildingStatus);
  })
  .catch(error => {
    console.warn('Error checking build status:', error);
    callback(null);
  });
}

function installGraphExplainButton() {
  if (graphBuildStatus == 0) {
    return;
  }

  if (document.querySelector('.explain-error-graph-btn')) {
    return;
  }

  const overflowRoot = document.getElementById('console-pipeline-overflow-root');
  if (!overflowRoot) {
    setTimeout(installGraphExplainButton, 500);
    return;
  }
  overflowRoot.style.display = 'contents';

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'jenkins-button explain-error-graph-btn';
  button.textContent = 'Explain selected step';
  button.onclick = function () {
    sendNodeExplainRequest(false);
  };
  overflowRoot.appendChild(button);
  Behaviour.applySubtree(overflowRoot, true);
  updateGraphExplainButton();
}

function removeGraphExplainButton() {
  const button = document.querySelector('.explain-error-graph-btn');
  if (button) {
    button.remove();
  }
}

function installHistoryListener() {
  if (window.explainErrorGraphHistoryInstalled) {
    return;
  }
  window.explainErrorGraphHistoryInstalled = true;

  const originalPushState = history.pushState;
  history.pushState = function () {
    const result = originalPushState.apply(this, arguments);
    setTimeout(function() {
      installGraphExplainButton();
      updateGraphExplainButton();
    }, 0);
    return result;
  };

  const originalReplaceState = history.replaceState;
  history.replaceState = function () {
    const result = originalReplaceState.apply(this, arguments);
    setTimeout(function() {
      installGraphExplainButton();
      updateGraphExplainButton();
    }, 0);
    return result;
  };

  window.addEventListener('popstate', function() {
    installGraphExplainButton();
    updateGraphExplainButton();
  });
}

function observeGraphViewChanges() {
  const root = document.getElementById('console-pipeline-root');
  if (!root) {
    setTimeout(observeGraphViewChanges, 500);
    return;
  }

  const observer = new MutationObserver(function() {
    installGraphExplainButton();
    updateGraphExplainButton();
  });
  observer.observe(root, { childList: true, subtree: true });

  const overflowRoot = document.getElementById('console-pipeline-overflow-root');
  if (overflowRoot) {
    observer.observe(overflowRoot, { childList: true });
  }

  // Install the button immediately in case the pipeline graph is already rendered.
  // This handles the case where the user navigates to this page with ?selected-node
  // from the pipeline overview and the DOM is already settled.
  installGraphExplainButton();
}

function getSelectedNodeId() {
  const params = new URLSearchParams(window.location.search);
  const nodeId = params.get('selected-node');
  if (nodeId) {
    return nodeId.trim();
  }
  // Fallback: detect the selected node from the DOM when the URL does not
  // contain ?selected-node=. The pipeline-graph-view auto-selects the worst
  // node (e.g. failed) on initial page load via React internal state without
  // calling history.replaceState, so the URL is not updated.
  const activeItem = document.querySelector('.pgv-tree-item--active');
  if (activeItem && activeItem.href) {
    const q = activeItem.href.indexOf('?');
    if (q !== -1) {
      const domParams = new URLSearchParams(activeItem.href.substring(q));
      const domNodeId = domParams.get('selected-node');
      if (domNodeId) {
        return domNodeId.trim();
      }
    }
  }
  return '';
}

function updateGraphExplainButton() {
  const button = document.querySelector('.explain-error-graph-btn');
  if (!button) {
    return;
  }

  const nodeId = getSelectedNodeId();
  const container = document.getElementById('explain-error-container');
  const enabled = container && container.dataset.pluginEnabled === 'true';
  const building = graphBuildStatus == 1;
  button.disabled = !enabled || !nodeId || building;
  button.setAttribute('tooltip', building
      ? 'Build is still running'
      : (nodeId ? 'Explain Pipeline node ' + nodeId : 'Select a Pipeline step first'));
}

Behaviour.specify(".eep-generate-new-button", "ExplainErrorGraphView", 0, function(e) {
  e.onclick = function(event) {
    event.preventDefault();
    sendNodeExplainRequest(true);
  };
});

Behaviour.specify(".eep-close-button", "ExplainErrorGraphView", 0, function(e) {
  e.onclick = function(event) {
    event.preventDefault();
    hideGraphContainer();
  };
});

function sendNodeExplainRequest(forceNew = false) {
  const nodeId = getSelectedNodeId();
  if (!nodeId) {
    notificationBar.show('Select a Pipeline step first', notificationBar.WARNING);
    return;
  }

  const container = document.getElementById('explain-error-container');
  const basePath = container.dataset.runUrl;
  const rootURL = document.head.getAttribute("data-rooturl");
  const url = rootURL + '/' + basePath + 'console-explain-error/explainNodeError';
  const headers = crumb.wrap({
    "Content-Type": "application/x-www-form-urlencoded",
  });
  const body = new URLSearchParams();
  body.set('nodeId', nodeId);
  if (forceNew) {
    body.set('forceNew', 'true');
  }

  showGraphSpinner();

  fetch(url, {
    method: "POST",
    headers: headers,
    body: body.toString()
  })
  .then(response => {
    if (!response.ok) {
      notificationBar.show('Explain failed', notificationBar.ERROR);
    }
    return response.json();
  })
  .then(json => {
    if (json.status == "success") {
      showGraphErrorExplanation(json.message, json.providerName, json.url);
    } else {
      if (json.status == "warning") {
        notificationBar.show(json.message, notificationBar.WARNING);
      } else {
        notificationBar.show(json.message, notificationBar.ERROR);
      }
      hideGraphContainer();
    }
  })
  .catch(error => {
    notificationBar.show(`Error: ${error.message}`, notificationBar.ERROR);
    hideGraphContainer();
  });
}

function showGraphErrorExplanation(message, providerName, url) {
  const container = document.getElementById('explain-error-container');
  const spinner = document.getElementById('explain-error-spinner');
  const content = document.getElementById('explain-error-content');
  const urlString = document.getElementById('explain-error-url');
  const cardTitle = container.querySelector('.jenkins-card__title');
  if (cardTitle && cardTitle.firstChild) {
    cardTitle.firstChild.textContent = `AI Step Explanation (${providerName})`;
  }
  container.classList.remove('jenkins-hidden');
  spinner.classList.add('jenkins-hidden');
  content.textContent = message;
  content.classList.remove('jenkins-hidden');
  urlString.classList.remove('jenkins-hidden');
  urlString.href = url;
}

function showGraphSpinner() {
  const container = document.getElementById('explain-error-container');
  const spinner = document.getElementById('explain-error-spinner');
  container.classList.remove('jenkins-hidden');
  spinner.classList.remove('jenkins-hidden');
}

function hideGraphContainer() {
  const container = document.getElementById('explain-error-container');
  container.classList.add('jenkins-hidden');
}

