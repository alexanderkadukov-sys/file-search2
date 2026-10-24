"use strict";
(function () {
  const $ = (id) => document.getElementById(id);
  const cfg = window.OPENCHAT_CONFIG || {};
  const rooms = {
    general: { title: "Общий чат", icon: "#" },
    photos: { title: "Фото", icon: "◈" },
    files: { title: "Файлы", icon: "▤" }
  };
  const allowed = new Set(["image/jpeg", "image/png", "image/webp", "image/gif", "application/pdf", "text/plain"]);
  const maxBytes = 8 * 1024 * 1024;
  const state = { room: "general", nickname: localStorage.getItem("openchat-name") || "", userId: null, client: null, live: false, channel: null, messages: [], selectedFile: null, switching: 0, sending: false };
  let toastTimer;
  const el = {
    roomTitle: $("room-title"), topIcon: $("top-icon"), presence: $("presence"), messages: $("messages"),
    scroll: $("message-scroll"), empty: $("empty"), input: $("message-input"), file: $("file-input"), send: $("send-button"),
    preview: $("attachment-preview"), previewName: $("attachment-title"), previewSize: $("attachment-size"),
    modal: $("name-modal"), nameInput: $("name-input"), name: $("my-name"), myAvatar: $("my-avatar"),
    toast: $("toast"), search: $("search-input")
  };
  const demoKey = "openchat-demo-v1";
  const isConfigured = Boolean(cfg.supabaseUrl && cfg.supabaseAnonKey &&
    /^https:\/\/[a-z0-9-]+\.supabase\.co\/?$/i.test(cfg.supabaseUrl) &&
    !/YOUR_|PLACEHOLDER/i.test(cfg.supabaseAnonKey));
  const show = (id) => { $(id).hidden = false; };
  const hide = (id) => { $(id).hidden = true; };
  const bytes = (n) => n < 1024 ? n + " Б" : n < 1048576 ? (n / 1024).toFixed(1) + " КБ" : (n / 1048576).toFixed(1) + " МБ";
  const time = (v) => new Date(v).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
  const avatarLetter = (name) => (name || "?").slice(0, 1).toUpperCase();
  const hash = (str) => Array.from(str || "").reduce((acc, ch) => (acc * 31 + ch.codePointAt(0)) >>> 0, 0);
  function avatar(name, mine) {
    const node = document.createElement("span");
    node.className = "avatar" + (mine ? " me" : "");
    node.textContent = avatarLetter(name);
    if (!mine) {
      const colors = [["#fce6d5", "#a96020"], ["#dbeafc", "#3866bc"], ["#dff5ed", "#308c69"], ["#f2e5ff", "#854dba"]];
      const c = colors[hash(name) % colors.length];
      node.style.backgroundColor = c[0]; node.style.color = c[1];
    }
    return node;
  }
  function toast(message) {
    el.toast.textContent = message;
    el.toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.toast.hidden = true, 4200);
  }
  function demoRead() {
    try {
      const raw = JSON.parse(localStorage.getItem(demoKey) || "[]");
      return Array.isArray(raw) ? raw : [];
    } catch (_) { return []; }
  }
  function demoSave(row) {
    const all = demoRead();
    all.push(row);
    try { localStorage.setItem(demoKey, JSON.stringify(all.slice(-120))); }
    catch (_) { toast("Недостаточно места для сохранения демо-сообщений"); }
  }
  function demoForRoom() {
    const all = demoRead().filter((x) => x.room === state.room);
    if (all.length) return all.slice(-70);
    if (state.room === "general") return [{
      id: "welcome", room: "general", sender_id: "system", nickname: "Команда OpenChat",
      body: "Привет! 👋 Добро пожаловать в OpenChat. Здесь можно общаться без регистрации, отправлять фото и файлы.\n\nСейчас открыт демонстрационный режим — для общего чата понадобится настроить базу данных.",
      created_at: new Date().toISOString()
    }];
    return [];
  }
  function scrollBottom() {
    requestAnimationFrame(() => { el.scroll.scrollTop = el.scroll.scrollHeight; });
  }
  function appendText(parent, cls, str) {
    const p = document.createElement("p"); p.className = cls; p.textContent = str; parent.appendChild(p);
  }
  function render() {
    const query = (el.search.value || "").trim().toLocaleLowerCase("ru");
    const list = query ? state.messages.filter((m) => (m.body + " " + m.nickname + " " + (m.file_name || "")).toLocaleLowerCase("ru").includes(query)) : state.messages;
    el.messages.replaceChildren();
    el.empty.hidden = list.length > 0;
    for (const m of list) {
      const mine = Boolean(state.userId && m.sender_id === state.userId);
      const row = document.createElement("article");
      row.className = "message-row" + (mine ? " mine" : "");
      row.appendChild(avatar(m.nickname, mine));
      const content = document.createElement("div"); content.className = "bubble-content";
      const name = document.createElement("div"); name.className = "sender"; name.textContent = m.nickname || "Гость"; content.appendChild(name);
      const bubble = document.createElement("div"); bubble.className = "bubble";
      if (m.file_path && state.live && state.client) {
        const url = state.client.storage.from(cfg.storageBucket || "chat-uploads").getPublicUrl(m.file_path).data.publicUrl;
        attachMedia(bubble, url, m.file_type, m.file_name, m.file_size);
      } else if (m.demo_attachment && m.demo_attachment.url) {
        attachMedia(bubble, m.demo_attachment.url, m.demo_attachment.type, m.demo_attachment.name, m.demo_attachment.size);
      }
      if (m.body) appendText(bubble, "body-text", m.body);
      const footer = document.createElement("div"); footer.className = "bubble-footer";
      const timestamp = document.createElement("time"); timestamp.dateTime = m.created_at; timestamp.textContent = time(m.created_at); footer.appendChild(timestamp);
      if (m.body) {
        const copy = document.createElement("button"); copy.type = "button"; copy.textContent = "⧉"; copy.title = "Скопировать сообщение";
        copy.addEventListener("click", async () => {
          try { await navigator.clipboard.writeText(m.body); toast("Текст скопирован"); }
          catch (_) { toast("Скопировать не удалось"); }
        });
        footer.appendChild(copy);
      }
      bubble.appendChild(footer); content.appendChild(bubble); row.appendChild(content); el.messages.appendChild(row);
    }
    if (!query) scrollBottom();
  }
  function attachMedia(target, url, type, name, size) {
    const label = name || "Вложение";
    if ((type || "").startsWith("image/")) {
      const img = document.createElement("img");
      img.className = "image-attachment"; img.src = url; img.alt = label; img.loading = "lazy"; img.referrerPolicy = "no-referrer";
      img.addEventListener("click", () => { $("lightbox-image").src = url; show("lightbox"); });
      target.appendChild(img);
    } else {
      const a = document.createElement("a");
      a.className = "file-attachment"; a.href = url; a.target = "_blank"; a.rel = "noopener noreferrer";
      const icon = document.createElement("span"); icon.className = "file-icon"; icon.textContent = "▤";
      const wrapper = document.createElement("span");
      const b = document.createElement("b"); b.textContent = label;
      const s = document.createElement("small"); s.textContent = bytes(size || 0) + " · Открыть файл";
      wrapper.append(b, s); a.append(icon, wrapper); target.appendChild(a);
    }
  }
  function updateNickname() {
    el.name.textContent = state.nickname || "Гость";
    el.myAvatar.textContent = avatarLetter(state.nickname);
  }
  function drawer(open) {
    $("sidebar").classList.toggle("open", open);
    $("scrim").hidden = !open;
  }
  async function connect() {
    if (!isConfigured || !window.supabase) {
      show("demo-banner");
      state.userId = localStorage.getItem("openchat-demo-id");
      if (!state.userId) {
        state.userId = "demo-" + (globalThis.crypto && crypto.randomUUID ? crypto.randomUUID() : String(Date.now()));
        localStorage.setItem("openchat-demo-id", state.userId);
      }
      return;
    }
    try {
      state.client = window.supabase.createClient(cfg.supabaseUrl.replace(/\/$/, ""), cfg.supabaseAnonKey, { auth: { persistSession: true, autoRefreshToken: true } });
      let { data: userData, error: userError } = await state.client.auth.getUser();
      if (userError || !userData.user) {
        const signin = await state.client.auth.signInAnonymously();
        if (signin.error) throw signin.error;
        userData = { user: signin.data.user };
      }
      state.userId = userData.user.id;
      state.live = true;
      hide("demo-banner");
    } catch (err) {
      show("demo-banner");
      state.live = false;
      state.client = null;
      state.userId = localStorage.getItem("openchat-demo-id") || "demo-" + Date.now();
      localStorage.setItem("openchat-demo-id", state.userId);
      toast("Сервер недоступен. Открыт локальный демо-режим.");
      console.warn("OpenChat connection:", err.message);
    }
  }
  async function switchRoom(nextRoom) {
    if (!rooms[nextRoom]) return;
    const request = ++state.switching;
    state.room = nextRoom;
    $("room-title").textContent = rooms[nextRoom].title;
    $("top-icon").textContent = rooms[nextRoom].icon;
    $("top-icon").style.background = nextRoom === "photos" ? "#ffedf5" : nextRoom === "files" ? "#eaf4ff" : "#f0efff";
    document.querySelectorAll("[data-room]").forEach((btn) => btn.classList.toggle("active", btn.dataset.room === nextRoom));
    const unread = document.querySelector('[data-unread="' + nextRoom + '"]');
    if (unread) unread.hidden = true;
    $("presence").textContent = state.live ? "Подключение..." : "Демонстрационный режим";
    drawer(false);
    if (state.channel && state.client) {
      await state.client.removeChannel(state.channel).catch(() => {});
      state.channel = null;
    }
    if (!state.live) {
      state.messages = demoForRoom();
      render(); return;
    }
    const result = await state.client.from("chat_messages").select("id,room,sender_id,nickname,body,file_path,file_name,file_type,file_size,created_at")
      .eq("room", nextRoom).order("created_at", { ascending: false }).limit(70);
    if (request !== state.switching) return;
    if (result.error) { toast("Не удалось загрузить переписку: проверьте SQL и RLS"); console.warn(result.error); state.messages = []; }
    else state.messages = result.data.reverse();
    render();
    const channel = state.client.channel("openchat-" + nextRoom, { config: { presence: { key: state.userId } } });
    channel.on("postgres_changes", { event: "INSERT", schema: "public", table: "chat_messages", filter: "room=eq." + nextRoom }, (payload) => {
      if (state.room !== nextRoom || state.messages.some((m) => m.id === payload.new.id)) return;
      state.messages.push(payload.new);
      state.messages = state.messages.slice(-100);
      render();
    });
    channel.on("presence", { event: "sync" }, () => {
      const groups = channel.presenceState();
      const count = Object.keys(groups).length;
      $("presence").textContent = count ? count + " в сети" : "Открыто для всех";
    });
    channel.subscribe(async (status) => {
      if (status === "SUBSCRIBED") {
        await channel.track({ nickname: state.nickname || "Гость", at: new Date().toISOString() }).catch(() => {});
      } else if (status === "CHANNEL_ERROR" || status === "TIMED_OUT") {
        $("presence").textContent = "Переподключение...";
      }
    });
    state.channel = channel;
  }
  function validateFile(file) {
    if (!file) return false;
    if (file.size > maxBytes) { toast("Файл больше 8 МБ"); return false; }
    if (!allowed.has(file.type)) { toast("Разрешены JPG, PNG, WEBP, GIF, PDF и TXT"); return false; }
    return true;
  }
  function chooseFile(file) {
    if (!validateFile(file)) return;
    state.selectedFile = file;
    el.previewName.textContent = file.name;
    el.previewSize.textContent = bytes(file.size);
    show("attachment-preview");
  }
  function resetFile() {
    state.selectedFile = null; el.file.value = ""; hide("attachment-preview");
  }
  function safeFilename(name) {
    return (name || "file").normalize("NFKC").replace(/[^a-z0-9._-]/gi, "_").slice(-90) || "file";
  }
  async function sendMessage(event) {
    if (event) event.preventDefault();
    if (state.sending || !state.nickname) return;
    const body = el.input.value.trim();
    const file = state.selectedFile;
    if (!body && !file) return;
    if (!state.userId) return toast("Подключение ещё не завершено");
    const destination = state.room;
    state.sending = true; el.send.disabled = true;
    let path = null;
    try {
      if (state.live) {
        if (file) {
          path = state.userId + "/" + crypto.randomUUID() + "-" + safeFilename(file.name);
          const uploaded = await state.client.storage.from(cfg.storageBucket || "chat-uploads")
            .upload(path, file, { contentType: file.type, upsert: false, cacheControl: "3600" });
          if (uploaded.error) throw uploaded.error;
        }
        const inserted = await state.client.from("chat_messages").insert({
          room: destination, sender_id: state.userId, nickname: state.nickname,
          body, file_path: path, file_name: file ? file.name.slice(0, 180) : null,
          file_type: file ? file.type : null, file_size: file ? file.size : null
        }).select("id,room,sender_id,nickname,body,file_path,file_name,file_type,file_size,created_at").single();
        if (inserted.error) throw inserted.error;
        if (state.room === destination && !state.messages.some((m) => m.id === inserted.data.id)) {
          state.messages.push(inserted.data); state.messages = state.messages.slice(-100); render();
        }
      } else {
        const row = {
          id: crypto && crypto.randomUUID ? crypto.randomUUID() : String(Date.now()), room: destination,
          sender_id: state.userId, nickname: state.nickname, body, created_at: new Date().toISOString()
        };
        if (file) row.demo_attachment = { url: URL.createObjectURL(file), name: file.name, type: file.type, size: file.size };
        if (!file) demoSave(row);
        if (state.room === destination) { state.messages.push(row); render(); }
        if (file) toast("Демо-файл сохранится только до обновления страницы");
      }
      el.input.value = ""; resetFile(); fitTextarea();
    } catch (err) {
      if (path && state.client) {
        await state.client.storage.from(cfg.storageBucket || "chat-uploads").remove([path]).catch(() => {});
      }
      const msg = String(err.message || "Ошибка").toLowerCase();
      toast(msg.includes("rate") || msg.includes("too many") || msg.includes("част") ? "Слишком частые сообщения. Повторите позже." : "Отправка не удалась. Проверьте подключение и настройки.");
      console.error("OpenChat send:", err);
    } finally {
      state.sending = false; el.send.disabled = false; el.input.focus();
    }
  }
  function fitTextarea() {
    el.input.style.height = "auto";
    el.input.style.height = Math.min(el.input.scrollHeight, 138) + "px";
  }
  function closeInfo() { hide("info-modal"); }
  function bindEvents() {
    $("open-menu").addEventListener("click", () => drawer(true));
    $("close-menu").addEventListener("click", () => drawer(false));
    $("scrim").addEventListener("click", () => drawer(false));
    document.querySelectorAll("[data-room]").forEach((btn) => btn.addEventListener("click", () => switchRoom(btn.dataset.room)));
    $("edit-name").addEventListener("click", () => { el.nameInput.value = state.nickname; show("name-modal"); el.nameInput.focus(); drawer(false); });
    $("name-form").addEventListener("submit", async (event) => {
      event.preventDefault(); const name = el.nameInput.value.trim().replace(/\s+/g, " ");
      if (name.length < 2 || name.length > 24) return toast("Имя должно содержать от 2 до 24 символов");
      state.nickname = name; localStorage.setItem("openchat-name", name); updateNickname(); hide("name-modal");
      if (state.channel && state.live) state.channel.track({ nickname: name }).catch(() => {});
      if (!state.messages.length) await switchRoom(state.room);
      el.input.focus();
    });
    $("info-toggle").addEventListener("click", () => show("info-modal"));
    $("info-close").addEventListener("click", closeInfo);
    $("info-ok").addEventListener("click", closeInfo);
    $("info-modal").addEventListener("click", (e) => { if (e.target.id === "info-modal") closeInfo(); });
    $("search-toggle").addEventListener("click", () => { show("search-panel"); el.search.focus(); });
    $("search-close").addEventListener("click", () => { hide("search-panel"); el.search.value = ""; render(); });
    el.search.addEventListener("input", render);
    $("attach-button").addEventListener("click", () => el.file.click());
    el.file.addEventListener("change", () => chooseFile(el.file.files[0]));
    $("attachment-remove").addEventListener("click", resetFile);
    $("compose-form").addEventListener("submit", sendMessage);
    el.input.addEventListener("input", fitTextarea);
    el.input.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey && !event.isComposing) { event.preventDefault(); sendMessage(); }
    });
    el.input.addEventListener("paste", (event) => {
      if (event.clipboardData && event.clipboardData.files.length) chooseFile(event.clipboardData.files[0]);
    });
    $("emoji-button").addEventListener("click", () => $("emoji-picker").hidden = !$("emoji-picker").hidden);
    $("emoji-picker").querySelectorAll("button").forEach((btn) => btn.addEventListener("click", () => {
      el.input.value += btn.textContent; fitTextarea(); hide("emoji-picker"); el.input.focus();
    }));
    $("lightbox-close").addEventListener("click", () => hide("lightbox"));
    $("lightbox").addEventListener("click", (e) => { if (e.target.id === "lightbox") hide("lightbox"); });
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") { hide("lightbox"); closeInfo(); hide("emoji-picker"); drawer(false); }
    });
    document.addEventListener("dragover", (e) => { if (e.dataTransfer && e.dataTransfer.types.includes("Files")) e.preventDefault(); });
    document.addEventListener("drop", (e) => {
      if (e.dataTransfer && e.dataTransfer.files.length) { e.preventDefault(); chooseFile(e.dataTransfer.files[0]); }
    });
  }
  async function start() {
    bindEvents(); updateNickname();
    if (!state.nickname) { show("name-modal"); el.nameInput.focus(); }
    await connect();
    await switchRoom("general");
  }
  start().catch((err) => { console.error(err); toast("Не удалось запустить приложение"); });
})();
