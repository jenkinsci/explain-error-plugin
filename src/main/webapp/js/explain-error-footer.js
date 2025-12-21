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
  checkBuildStatus(function(buildingStatus) {
    // Build status 2 is completed and it's UNSTABLE or FAILURE
    if (buildingStatus == 2) {
      // Build is completed, show the button
      addExplainErrorButton();
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
    callback(data.buildingStatus);
  })
  .catch(error => {
    console.warn('Error checking build status:', error);
    // If check fails, assume build is complete and show button
    callback(false);
  });
}

function addExplainErrorButton() {
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
  if (!enabled && !hasExplanation) {
      return;
  }
  const buttonText = 'Explain Error';
  const callback = hasExplanation ? showExistingExplanation : explainConsoleError;
  const explainBtn = createButton(buttonText, 'jenkins-button explain-error-btn', callback, providerName);

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
}

function showExistingExplanation() {
    const container = document.getElementById('explain-error-container');
    container.classList.remove('jenkins-hidden');
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

function explainConsoleError() {
  // First, check if an explanation already exists
    sendExplainRequest(false);
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

function generateNewExplanation() {
  clearExplanationContent();
  sendExplainRequest(true); // Force new explanation
}

function cancelExplanation() {
  hideConfirmationDialog();
}

function sendExplainRequest(forceNew = false) {
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
  .then(response => {
    if (!response.ok) {
      notificationBar.show('Explain failed', notificationBar.ERROR);
    }
    return response.json();
  })
  .then(json => {
    try {
      if (json.status == "success") {
        showErrorExplanation(json.message, json.providerName, json.url);
      }
      else {
        if (json.status == "warning") {
          notificationBar.show(json.message, notificationBar.WARNING);
        }
        else {
          notificationBar.show(json.message, notificationBar.ERROR);
        }
        hideContainer();
      }
    } catch (e) {
      notificationBar.show(`Error: ${error.message}`, notificationBar.ERROR);
    }
  })
  .catch(error => {
    notificationBar.show(`Error: ${error.message}`, notificationBar.ERROR);
  });
}

function showErrorExplanation(message, providerName, url) {
  const container = document.getElementById('explain-error-container');
  const spinner = document.getElementById('explain-error-spinner');
  const content = document.getElementById('explain-error-content');
  const urlString = document.getElementById('explain-error-url');
  const cardTitle = document.querySelector('.jenkins-card__title');
  cardTitle.firstChild.textContent = `AI Error Explanation (${providerName})`;
  container.classList.remove('jenkins-hidden');
  spinner.classList.add('jenkins-hidden');
  content.textContent = message;
  content.classList.remove('jenkins-hidden');
  urlString.classList.remove('jenkins-hidden');
  urlString.href = url;
}

function showSpinner() {
  const container = document.getElementById('explain-error-container');
  const spinner = document.getElementById('explain-error-spinner');
  container.classList.remove('jenkins-hidden');
  spinner.classList.remove('jenkins-hidden');
}

function hideContainer() {
  const container = document.getElementById('explain-error-container');
  container.classList.add('jenkins-hidden');
}

function clearExplanationContent() {
  const content = document.getElementById('explain-error-content');
    content.classList.add('jenkins-hidden');
    content.textContent = '';
}
