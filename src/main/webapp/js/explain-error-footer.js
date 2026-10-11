document.addEventListener('DOMContentLoaded', function () {
  checkBuildStatusAndAddButton();
  // Moved from the second DOMContentLoaded listener
  const container = document.getElementById('explain-error-container');
  const consoleOutput =
    document.querySelector('#out') ||
    document.querySelector('pre.console-output') ||
    document.querySelector('pre');
  if (container && consoleOutput && consoleOutput.parentNode) {
    consoleOutput.parentNode.insertBefore(container, consoleOutput);
  }
});

function checkBuildStatusAndAddButton() {
  checkBuildStatus(function(buildingStatus, explanation) {
    // Build status 2 is completed and it's UNSTABLE or FAILURE
    if (buildingStatus == 2) {
      // Build is completed, show the button
      addExplainErrorButton(explanation);
    } else if (buildingStatus == 1) {
      // Build is still running, check again after a delay
      setTimeout(checkBuildStatusAndAddButton, 5000); // Check every 5 seconds
    }
  });
}

function checkBuildStatus(callback) {
  const container = document.getElementById('explain-error-container');
  const basePath = container.dataset.runUrl
  const rootURL = document.head.getAttribute("data-rooturl");
  const url = rootURL + '/' + basePath + 'console-explain-error/checkBuildStatus';

  const headers = crumb.wrap({
    "Content-Type": "application/x-www-form-urlencoded",
  });

  fetch(url, {
    method: "POST",
    headers: headers,
    body: ""
  })
  .then(response => response.json())
  .then(data => {
    callback(data.buildingStatus, data.explanation);
  })
  .catch(error => {
    console.warn('Error checking build status:', error);
    // If check fails, assume build is complete and show button
    callback(false);
  });
}

// explanation: what the server says the build has now ("ready", "pending" or "none"). The page
// may have been rendered while the build was still running, so this wins over its own markup.
function addExplainErrorButton(explanation) {
  // Check if button already exists to prevent duplicates
  if (document.querySelector('.explain-error-btn')) {
    return;
  }

  // Try to find buttons by their text content
  let buttonContainer = null;
  const downloadButtons = Array.from(document.querySelectorAll('a, button')).filter(el => 
    el.textContent && (
      el.textContent.includes('Download') || 
      el.textContent.includes('Copy') || 
      el.textContent.includes('View as plain text')
    )
  );

  if (downloadButtons.length > 0) {
    buttonContainer = downloadButtons[0].parentElement;
  }

  // Fallback: find console output element
  const consoleOutput =
    document.querySelector('#out') ||
    document.querySelector('pre.console-output') ||
    document.querySelector('pre');

  if (!consoleOutput && !buttonContainer) {
    console.warn('Console output element not found');
    setTimeout(function() {
      // Only retry if the button doesn't exist yet and we're still on a console page
      if (!document.querySelector('.explain-error-btn')) {
        checkBuildStatusAndAddButton();
      }
    }, 3000);
    return;
  }

  const container = document.getElementById('explain-error-container');
  const providerName = container.dataset.providerName;
  const hasExplanation = container.dataset.hasExplanation === 'true';
  const enabled = container.dataset.pluginEnabled === 'true';
  const state = explanation || (hasExplanation ? 'ready' : 'none');
  if (!enabled && state === 'none') {
      return;
  }
  const explainBtn = createButton(container.dataset.labelExplain, 'jenkins-button explain-error-btn',
    openExplanation, providerName);

  // If we found the button container, add our button there
  if (buttonContainer) {
    buttonContainer.insertBefore(explainBtn, buttonContainer.firstChild);
    Behaviour.applySubtree(buttonContainer, true);
  } else {
    // Fallback: create a simple container above console output
    const container = document.createElement('div');
    container.className = 'explain-error-container';
    container.style.marginBottom = '10px';
    container.appendChild(explainBtn);
    consoleOutput.parentNode.insertBefore(container, consoleOutput);
    Behaviour.applySubtree(container, true);
  }

  setButtonState(state);
  if (state === 'pending') {
    waitForPendingExplanation();
  }
}

// The button says what a click will do: ask for an explanation, show the one the build has,
// or nothing yet because one is already being generated.
function setButtonState(state) {
  const btn = document.querySelector('.explain-error-btn');
  if (!btn) {
    return;
  }
  const labels = document.getElementById('explain-error-container').dataset;
  btn.disabled = state === 'pending';
  if (state === 'ready') {
    btn.textContent = labels.labelShow;
  } else if (state === 'pending') {
    btn.textContent = labels.labelPending;
  } else {
    btn.textContent = labels.labelExplain;
  }
}

// An automatic explanation is on its way: asking for another would pay for the same answer twice.
function waitForPendingExplanation() {
  setTimeout(function () {
    checkBuildStatus(function(buildingStatus, explanation) {
      if (explanation === 'pending') {
        waitForPendingExplanation();
      } else {
        setButtonState(explanation || 'none');
      }
    });
  }, 3000);
}

function openExplanation() {
  const container = document.getElementById('explain-error-container');
  const content = document.getElementById('explain-error-content');
  if (content.childElementCount > 0 && !isShowingFailure()) {
    container.classList.remove('jenkins-hidden');
    return;
  }
  // Nothing rendered yet: the server answers with the explanation the build has, or a new one
  sendExplainRequest(false);
}

function createButton(text, className, onClick, providerName) {
  const btn = document.createElement('button');
  btn.textContent = text;
  btn.className = className;
  btn.onclick = function() {
    onClick(false);
  };
  btn.setAttribute("tooltip", "Provider: " + providerName);
  return btn;
}

Behaviour.specify(".eep-generate-new-button", "ExplainErrorView", 0, function(e) {
  e.onclick = function(event) {
      event.preventDefault();
      generateNewExplanation();
  };
});

Behaviour.specify(".eep-close-button", "ExplainErrorView", 0, function(e) {
    e.onclick = function(event) {
        event.preventDefault();
        hideContainer();
    };
});

Behaviour.specify("#explain-error-retry", "ExplainErrorView", 0, function(e) {
    e.onclick = function(event) {
        event.preventDefault();
        sendExplainRequest(lastRequestForcedNew);
    };
});

function generateNewExplanation() {
  sendExplainRequest(true); // Force new explanation
}

function cancelExplanation() {
  hideConfirmationDialog();
}

// What "Try again" repeats
let lastRequestForcedNew = false;

function sendExplainRequest(forceNew = false) {
  lastRequestForcedNew = forceNew;
  const container = document.getElementById('explain-error-container');
  const basePath = container.dataset.runUrl
  const rootURL = document.head.getAttribute("data-rooturl");
  const url = rootURL + '/' + basePath + 'console-explain-error/explainConsoleError';

  const headers = crumb.wrap({
    "Content-Type": "application/x-www-form-urlencoded",
  });

  // Add forceNew parameter if needed
  const body = forceNew ? "forceNew=true" : "";

  showSpinner();

  fetch(url, {
    method: "POST",
    headers: headers,
    body: body
  })
  .then(parseJsonResponse)
  .then(json => {
    if (json.status == "success") {
      showErrorExplanation(json.message, json.providerName, json.url, json.title);
    } else if (json.reason) {
      // The server knows what went wrong; what the provider sent back is the detail
      showFailure(json.reason, json.message, json.status == "warning");
    } else {
      showFailure(json.message, null, json.status == "warning");
    }
  })
  .catch(error => {
    showFailure(error.message, null, false);
  });
}

// A failed request stays in the card, where it can be read and tried again.
function showFailure(headline, detail, isWarning) {
  const container = document.getElementById('explain-error-container');
  const spinner = document.getElementById('explain-error-spinner');
  const content = document.getElementById('explain-error-content');
  const failure = document.getElementById('explain-error-failure');
  const details = document.getElementById('explain-error-failure-details');

  document.getElementById('explain-error-failure-headline').textContent =
    headline || container.dataset.failureHeadline;
  document.getElementById('explain-error-failure-detail').textContent = detail || '';
  setClass(details, 'jenkins-hidden', !detail);
  details.open = false;
  setClass(failure, 'jenkins-alert-warning', isWarning);
  setClass(failure, 'jenkins-alert-danger', !isWarning);

  spinner.classList.add('jenkins-hidden');
  failure.classList.remove('jenkins-hidden');
  // A new explanation that failed leaves the build with the one it had: keep showing it
  setClass(content, 'jenkins-hidden', content.childElementCount === 0);
  container.classList.remove('jenkins-hidden');
}

function setClass(element, name, on) {
  if (on) {
    element.classList.add(name);
  } else {
    element.classList.remove(name);
  }
}

function isShowingFailure() {
  return !document.getElementById('explain-error-failure').classList.contains('jenkins-hidden');
}

function parseJsonResponse(response) {
  return response.text().then(text => {
    try {
      return JSON.parse(text);
    } catch (error) {
      const message = response.ok
        ? 'Explain failed: Jenkins returned an invalid response.'
        : `Explain failed with HTTP ${response.status}: ${summarizeHtmlResponse(text)}`;
      throw new Error(message);
    }
  });
}

function summarizeHtmlResponse(text) {
  if (!text) {
    return 'empty response';
  }
  const titleMatch = text.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  if (titleMatch) {
    return decodeHtml(titleMatch[1]).trim();
  }
  return text.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim().substring(0, 200);
}

function decodeHtml(text) {
  const textarea = document.createElement('textarea');
  textarea.innerHTML = text;
  return textarea.value;
}

function showErrorExplanation(message, providerName, url, title) {
  const container = document.getElementById('explain-error-container');
  const spinner = document.getElementById('explain-error-spinner');
  const content = document.getElementById('explain-error-content');
  const urlString = document.getElementById('explain-error-url');
  const cardTitle = document.querySelector('.jenkins-card__title');
  cardTitle.firstChild.textContent = `${title || 'AI Error Explanation'} (${providerName})`;
  container.classList.remove('jenkins-hidden');
  urlString.href = url;
  renderExplanation(content, message).then(() => {
    spinner.classList.add('jenkins-hidden');
    content.classList.remove('jenkins-hidden');
    urlString.classList.remove('jenkins-hidden');
    setButtonState('ready');
  });
}

// The explanation has just been stored on the build, so ask the server for it rendered
// (summary, steps, log excerpt) rather than building markup here. The plain text is the fallback.
function renderExplanation(content, message) {
  const container = document.getElementById('explain-error-container');
  const rootURL = document.head.getAttribute("data-rooturl");
  const url = rootURL + '/' + container.dataset.runUrl + 'error-explanation/panel';

  return fetch(url)
    .then(response => response.ok ? response.text() : Promise.reject(new Error(`HTTP ${response.status}`)))
    .then(html => {
      content.innerHTML = html;
    })
    .catch(() => {
      const plain = document.createElement('pre');
      plain.className = 'eep-plain';
      plain.textContent = message;
      content.replaceChildren(plain);
    });
}

function showSpinner() {
  const container = document.getElementById('explain-error-container');
  const spinner = document.getElementById('explain-error-spinner');
  // Out of sight, not thrown away: if the request fails, an earlier explanation is shown again
  document.getElementById('explain-error-content').classList.add('jenkins-hidden');
  document.getElementById('explain-error-failure').classList.add('jenkins-hidden');
  container.classList.remove('jenkins-hidden');
  spinner.classList.remove('jenkins-hidden');
}

function hideContainer() {
  const container = document.getElementById('explain-error-container');
  container.classList.add('jenkins-hidden');
}
