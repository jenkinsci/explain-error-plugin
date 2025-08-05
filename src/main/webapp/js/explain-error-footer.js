document.addEventListener('DOMContentLoaded', function () {
  if (
    window.location.pathname.match(/\/console(Full)?$/) &&
    !window.location.pathname.includes('/error-explanation')
  ) {
    checkBuildStatusAndAddButton();
  }
  // Moved from the second DOMContentLoaded listener
  const container = document.getElementById('explain-error-container');
  const consoleOutput =
    document.querySelector('#out') ||
    document.querySelector('pre.console-output') ||
    document.querySelector('pre');
  if (container && consoleOutput && consoleOutput.parentNode) {
    consoleOutput.parentNode.insertBefore(container, consoleOutput);
  }
  
  // Add the confirmation dialog to the page
  const dialogContainer = document.getElementById('explain-error-confirm-dialog');
  if (dialogContainer && consoleOutput && consoleOutput.parentNode) {
    consoleOutput.parentNode.insertBefore(dialogContainer, consoleOutput);
  }
});

function checkBuildStatusAndAddButton() {
  checkBuildStatus(function(isBuilding) {
    if (!isBuilding) {
      // Build is completed, show the button
      addExplainErrorButton();
    } else {
      // Build is still running, check again after a delay
      setTimeout(checkBuildStatusAndAddButton, 5000); // Check every 5 seconds
    }
  });
}

function checkBuildStatus(callback) {
  const basePath = window.location.pathname.replace(/\/console(Full)?$/, '');
  const url = basePath + '/console-explain-error/checkBuildStatus';

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
    callback(data.isBuilding);
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

  // First try to find the existing console button bar
  const consoleButtonBar = 
    document.querySelector('.console-actions') ||
    document.querySelector('.console-output-actions') ||
    document.querySelector('.console-controls') ||
    document.querySelector('[class*="console"][class*="button"]') ||
    document.querySelector('#console .btn-group') ||
    document.querySelector('.jenkins-button-bar');

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
      if (!document.querySelector('.explain-error-btn') && 
          window.location.pathname.match(/\/console(Full)?$/)) {
        checkBuildStatusAndAddButton();
      }
    }, 3000);
    return;
  }

  const explainBtn = createButton('Explain Error', 'jenkins-button explain-error-btn', explainConsoleError);

  // If we found the button container, add our button there
  if (buttonContainer) {
    buttonContainer.insertBefore(explainBtn, buttonContainer.firstChild);
  } else if (consoleButtonBar) {
    consoleButtonBar.appendChild(explainBtn);
  } else {
    // Fallback: create a simple container above console output
    const container = document.createElement('div');
    container.className = 'explain-error-container';
    container.style.marginBottom = '10px';
    container.appendChild(explainBtn);
    consoleOutput.parentNode.insertBefore(container, consoleOutput);
  }
}

function createButton(text, className, onClick) {
  const btn = document.createElement('button');
  btn.textContent = text;
  btn.className = className;
  btn.onclick = onClick;
  return btn;
}

function explainConsoleError() {
  // First, check if an explanation already exists
  checkExistingExplanation();
}

function checkExistingExplanation() {
  const basePath = window.location.pathname.replace(/\/console$/, '');
  const url = basePath + '/console-explain-error/checkExistingExplanation';

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
    if (data.hasExplanation) {
      // Show confirmation dialog
      showConfirmationDialog(data.timestamp);
    } else {
      // No existing explanation, proceed with new request
      sendExplainRequest(false);
    }
  })
  .catch(error => {
    console.warn('Error checking existing explanation:', error);
    // If check fails, proceed with new request
    sendExplainRequest(false);
  });
}

function showConfirmationDialog(timestamp) {
  const dialog = document.getElementById('explain-error-confirm-dialog');
  const timestampSpan = document.getElementById('existing-explanation-timestamp');
  
  if (timestampSpan) {
    timestampSpan.textContent = timestamp;
  }
  
  dialog.classList.remove('jenkins-hidden');
  
  // Hide other elements
  hideContainer();
}

function viewExistingExplanation() {
  hideConfirmationDialog();
  sendExplainRequest(false); // This will return the cached result
}

function generateNewExplanation() {
  hideConfirmationDialog();
  clearExplanationContent();
  sendExplainRequest(true); // Force new explanation
}

function cancelExplanation() {
  hideConfirmationDialog();
}

function hideConfirmationDialog() {
  const dialog = document.getElementById('explain-error-confirm-dialog');
  dialog.classList.add('jenkins-hidden');
}

function sendExplainRequest(forceNew = false) {
  const basePath = window.location.pathname.replace(/\/console$/, '');
  const url = basePath + '/console-explain-error/explainConsoleError';

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
    return response.text();
  })
  .then(responseText => {
    try {
      const jsonResponse = JSON.parse(responseText);
      showErrorExplanation(jsonResponse);
    } catch (e) {
      showErrorExplanation(responseText);
    }
  })
  .catch(error => {
    showErrorExplanation(`Error: ${error.message}`);
  });
}

function showErrorExplanation(message) {
  const container = document.getElementById('explain-error-container');
  const spinner = document.getElementById('explain-error-spinner');
  const content = document.getElementById('explain-error-content');

  container.classList.remove('jenkins-hidden');
  spinner.classList.add('jenkins-hidden');
  content.textContent = message;
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
  if (content) {
    content.textContent = '';
  }
}
