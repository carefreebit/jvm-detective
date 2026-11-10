(function () {
  "use strict";

  var state = "ALL";
  var search = document.getElementById("thread-search");
  var buttons = Array.prototype.slice.call(document.querySelectorAll(".filter"));

  function applyFilters() {
    var q = (search && search.value ? search.value : "").toLowerCase();
    var shown = 0;
    document.querySelectorAll(".thread").forEach(function (el) {
      var okState = state === "ALL" || el.dataset.state === state;
      var okText = !q || (el.dataset.search || "").indexOf(q) >= 0;
      var visible = okState && okText;
      el.classList.toggle("hidden", !visible);
      if (visible) {
        shown++;
      }
    });
    var counter = document.getElementById("thread-visible");
    if (counter) {
      counter.textContent = shown;
    }
  }

  buttons.forEach(function (button) {
    button.addEventListener("click", function () {
      state = button.dataset.filter;
      buttons.forEach(function (b) { b.classList.toggle("on", b === button); });
      applyFilters();
    });
  });

  if (search) {
    search.addEventListener("input", applyFilters);
  }

  var toast = document.getElementById("toast");
  function showToast(message) {
    if (!toast) {
      return;
    }
    toast.textContent = message;
    toast.classList.add("show");
    window.setTimeout(function () { toast.classList.remove("show"); }, 1800);
  }

  function copyText(text, okMessage) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () {
        showToast(okMessage);
      }).catch(function () {
        fallbackCopy(text, okMessage);
      });
    } else {
      fallbackCopy(text, okMessage);
    }
  }

  function fallbackCopy(text, okMessage) {
    var area = document.createElement("textarea");
    area.value = text;
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    try {
      document.execCommand("copy");
      showToast(okMessage);
    } catch (e) {
      showToast("COPY FAILED");
    }
    document.body.removeChild(area);
  }

  window.copyPrompt = function () {
    var area = document.getElementById("ai-prompt");
    if (area) {
      copyText(area.value, "AI PROMPT COPIED");
    }
  };

  window.copyCommand = function () {
    var area = document.getElementById("repro-command");
    if (area) {
      copyText(area.value, "COMMAND COPIED");
    }
  };

  document.querySelectorAll("[data-copy]").forEach(function (button) {
    button.addEventListener("click", function () {
      var target = document.getElementById(button.dataset.copy);
      if (target) {
        copyText(target.value || target.textContent, "COPIED");
      }
    });
  });
})();
