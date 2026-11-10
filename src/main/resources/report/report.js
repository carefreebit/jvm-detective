(function () {
  "use strict";

  var state = "ALL";
  var lang = "en";
  var search = document.getElementById("thread-search");
  var buttons = Array.prototype.slice.call(document.querySelectorAll(".filter"));

  /* ---------- language ---------- */

  var toastText = {
    en: { prompt: "AI PROMPT COPIED", command: "COMMAND COPIED", copied: "COPIED", failed: "COPY FAILED" },
    zh: { prompt: "已复制 AI PROMPT", command: "已复制命令", copied: "已复制", failed: "复制失败" }
  };

  function applyLang(next, persist) {
    lang = next === "zh" ? "zh" : "en";
    document.documentElement.lang = lang === "zh" ? "zh-CN" : "en";

    document.querySelectorAll("[data-en]").forEach(function (el) {
      var value = lang === "zh" ? el.dataset.zh : el.dataset.en;
      if (value !== undefined && value !== null) {
        el.textContent = value;
      }
    });
    document.querySelectorAll("[data-en-placeholder]").forEach(function (el) {
      var value = lang === "zh" ? el.dataset.zhPlaceholder : el.dataset.enPlaceholder;
      if (value !== undefined && value !== null) {
        el.setAttribute("placeholder", value);
      }
    });
    document.querySelectorAll(".lang [data-lang]").forEach(function (button) {
      button.classList.toggle("on", button.dataset.lang === lang);
    });
    if (persist) {
      try {
        localStorage.setItem("jvmd-lang", lang);
      } catch (e) {
        /* file:// or privacy mode: language still applies for this page view */
      }
    }
  }

  document.querySelectorAll(".lang [data-lang]").forEach(function (button) {
    button.addEventListener("click", function () {
      applyLang(button.dataset.lang, true);
    });
  });

  var stored = null;
  try {
    stored = localStorage.getItem("jvmd-lang");
  } catch (e) {
    stored = null;
  }
  var fromUrl = new URLSearchParams(window.location.search).get("lang");
  var fromBrowser = (navigator.language || "").toLowerCase().indexOf("zh") === 0 ? "zh" : "en";
  applyLang(stored || fromUrl || fromBrowser, false);

  /* ---------- thread filters ---------- */

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

  /* ---------- copy helpers ---------- */

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
      showToast(toastText[lang].failed);
    }
    document.body.removeChild(area);
  }

  window.copyPrompt = function () {
    var area = document.getElementById("ai-prompt-" + lang);
    if (area) {
      copyText(area.value, toastText[lang].prompt);
    }
  };

  window.copyCommand = function () {
    var area = document.getElementById("repro-command");
    if (area) {
      copyText(area.value, toastText[lang].command);
    }
  };

  document.querySelectorAll("[data-copy]").forEach(function (button) {
    button.addEventListener("click", function () {
      var target = document.getElementById(button.dataset.copy);
      if (target) {
        copyText(target.value || target.textContent, toastText[lang].copied);
      }
    });
  });
})();
