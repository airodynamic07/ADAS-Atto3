// On a video page, mark the page so fit.css shows only the player, filling the window.
// YouTube changes pages without reloading, so check every half second.
(function () {
  function check() {
    var watch = location.pathname === '/watch' || location.pathname.indexOf('/shorts/') === 0;
    document.documentElement.classList.toggle('natee-watch', watch);
    document.documentElement.classList.toggle('natee-fit', location.hash.indexOf('natee-fit') >= 0);
  }
  check();
  setInterval(check, 500);
})();
