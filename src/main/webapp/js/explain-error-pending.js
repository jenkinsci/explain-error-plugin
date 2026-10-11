// On the build page, while an automatic explanation is being generated: ask now and then
// whether it is in, and reload so that its summary takes the place of the spinner.
(function () {
  const INTERVAL = 3000;
  let checksLeft = 200; // ten minutes; after that the reader reloads by hand

  function check() {
    const pending = document.getElementById('explain-error-pending');
    if (!pending || checksLeft-- <= 0) {
      return;
    }
    fetch(pending.dataset.statusUrl, { method: 'POST', headers: crumb.wrap({}) })
      .then(response => response.json())
      .then(data => {
        if (data.explanation === 'pending') {
          setTimeout(check, INTERVAL);
        } else {
          window.location.reload();
        }
      })
      .catch(() => setTimeout(check, INTERVAL));
  }

  document.addEventListener('DOMContentLoaded', function () {
    setTimeout(check, INTERVAL);
  });
})();
