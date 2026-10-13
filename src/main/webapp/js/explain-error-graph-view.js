/**
 * The Explain Error button and card on the "Stages" page of the Pipeline Graph View plugin.
 *
 * The card, and everything it does once the button is clicked, is the console page's: see
 * explain-error-footer.js. This file only decides when the button is there and where the card
 * goes. Both follow the stage or step selected in the graph.
 *
 * Graph View offers no extension point for this, so the selection is read from its page: the
 * selected-node URL parameter and the tree item it marks active. If that markup changes, the
 * button does not show; nothing else on the page depends on it.
 */
(function () {
  'use strict';

  const CHECK_SELECTION_MS = 300;
  const CHECK_BUILD_MS = 5000;
  const CHECK_PENDING_MS = 3000;

  let container = null;
  let button = null;
  // The flow node the button and the card are about, as a string, or null
  let selectedNode = null;

  document.addEventListener('DOMContentLoaded', function () {
    container = document.getElementById('explain-error-container');
    if (!container || container.dataset.surface !== 'graph-view') {
      return;
    }
    whenBuildHasFailed(function () {
      followSelection();
      setInterval(followSelection, CHECK_SELECTION_MS);
    });
  });

  function post(path, body) {
    const rootURL = document.head.getAttribute('data-rooturl');
    return fetch(rootURL + '/' + container.dataset.runUrl + 'graph-explain-error/' + path, {
      method: 'POST',
      headers: crumb.wrap({ 'Content-Type': 'application/x-www-form-urlencoded' }),
      body: body
    }).then(response => response.json());
  }

  // 0 = succeeded, 1 = still running, 2 = finished without success
  function whenBuildHasFailed(callback) {
    post('checkBuildStatus', '')
      .then(data => {
        if (data.buildingStatus === 2) {
          callback();
        } else if (data.buildingStatus === 1) {
          setTimeout(() => whenBuildHasFailed(callback), CHECK_BUILD_MS);
        }
      })
      .catch(error => console.warn('Explain Error: could not check the build status', error));
  }

  // Graph View puts the selection in the URL once something has been clicked. Until then it picks
  // a stage itself, the failed one if there is one, and only marks it in the tree.
  function currentNode() {
    const fromUrl = new URLSearchParams(window.location.search).get('selected-node');
    if (fromUrl) {
      return fromUrl;
    }
    const active = document.querySelector('a.pgv-tree-item--active[href*="selected-node="]');
    return active ? new URL(active.href).searchParams.get('selected-node') : null;
  }

  function followSelection() {
    const node = currentNode();
    if (node === selectedNode) {
      placeCard();
      return;
    }
    selectedNode = node;
    container.dataset.requestParams = node ? 'nodeId=' + encodeURIComponent(node) : '';
    // What the card shows belongs to the node that was selected before
    resetExplanationCard();
    setButtonVisible(false);
    placeCard();
    if (node) {
      checkNode(node);
    }
  }

  // The button is for a failed node only. Its label says what the build already has for it.
  function checkNode(node) {
    post('checkNodeStatus', 'nodeId=' + encodeURIComponent(node))
      .then(data => {
        if (node !== selectedNode) {
          return;
        }
        const enabled = container.dataset.pluginEnabled === 'true';
        if (!data.isFailed || (!enabled && data.explanation !== 'ready')) {
          setButtonVisible(false);
          return;
        }
        setButtonVisible(true);
        setButtonState(data.explanation);
        if (data.explanation === 'pending') {
          // An automatic explanation is on its way: look again rather than ask for a second one
          setTimeout(() => {
            if (node === selectedNode) {
              checkNode(node);
            }
          }, CHECK_PENDING_MS);
        }
      })
      .catch(error => {
        console.warn('Explain Error: could not check the selected node', error);
        if (node === selectedNode) {
          setButtonVisible(false);
        }
      });
  }

  function setButtonVisible(visible) {
    if (visible && !button) {
      button = addButton();
    }
    if (button) {
      setClass(button, 'jenkins-hidden', !visible);
    }
  }

  // Next to the page's own actions, in front of them so that Rerun and the settings stay put
  function addButton() {
    const controls = document.querySelector('.jenkins-app-bar__controls');
    if (!controls) {
      return null;
    }
    const btn = createButton(container.dataset.labelExplain,
      'jenkins-button explain-error-btn explain-error-graph-btn', openExplanation,
      container.dataset.providerName);
    btn.type = 'button';
    const first = Array.from(controls.children).find(child => child.tagName !== 'SCRIPT');
    controls.insertBefore(btn, first || null);
    Behaviour.applySubtree(btn, true);
    return btn;
  }

  // Under the header of the selected stage, above its steps, so the explanation is read next to
  // the log it is about. Graph View redraws that pane; the card is put back if it has moved.
  // Without the pane the card goes to the end of the page content.
  function placeCard() {
    const pane = document.getElementById('stage-view-pane');
    const header = pane && pane.querySelector('.pgv-stage-details');
    if (header) {
      if (header.nextElementSibling !== container) {
        header.after(container);
      }
      return;
    }
    const mainPanel = document.getElementById('main-panel');
    if (mainPanel && container.parentElement !== mainPanel) {
      mainPanel.appendChild(container);
    }
  }
})();
