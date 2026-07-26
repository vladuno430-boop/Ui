/* =========================================================
   Castielshop — interaction layer
   No dependencies. Every module fails soft on its own.
   ========================================================= */
(function () {
  'use strict';

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };
  var reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

  var store = {
    get: function (key, fallback) {
      try {
        var raw = localStorage.getItem(key);
        return raw === null ? fallback : JSON.parse(raw);
      } catch (e) { return fallback; }
    },
    set: function (key, value) {
      try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) {}
    }
  };

  /* ---------- Listing data ---------- */
  var LISTINGS = [
    { id: 'l01', cat: 'personal', art: 'bike', title: 'Велосипед горный, алюминиевая рама, 21 скорость', price: 54000, city: 'Москва', area: 'Тверская', age: 0, verified: true, delivery: true, urgent: false },
    { id: 'l02', cat: 'electronics', title: 'iPhone 15 Pro, 256 ГБ, состояние идеальное', price: 128500, city: 'Санкт-Петербург', area: 'Центральный', age: 1, verified: true, delivery: true, urgent: false },
    { id: 'l03', cat: 'realty', title: '1-комн. квартира, 38 м², с ремонтом', price: 45000, unit: '/мес', from: true, city: 'Казань', area: 'Вахитовский р-н', age: 0, verified: true, delivery: false, urgent: false },
    { id: 'l04', cat: 'home', title: 'Кресло офисное, сетчатая спинка, регулировка', price: 2300, city: 'Новосибирск', area: 'Ленинский р-н', age: 2, verified: false, delivery: true, urgent: false },
    { id: 'l05', cat: 'services', title: 'Услуги репетитора по математике, ЕГЭ/ОГЭ', price: 1500, unit: '/час', from: true, city: 'Москва', area: 'Онлайн и очно', age: 0, verified: true, delivery: false, urgent: false },
    { id: 'l06', cat: 'transport', title: 'Renault Duster 2019, 1.6 л, механика', price: 890000, city: 'Екатеринбург', area: 'Кировский р-н', age: 0, verified: true, delivery: false, urgent: true },
    { id: 'l07', cat: 'hobby', title: 'Гитара акустическая, чехол в комплекте', price: 6200, city: 'Краснодар', area: 'Западный округ', age: 3, verified: false, delivery: true, urgent: false },
    { id: 'l08', cat: 'pets', title: 'Отдам котят в добрые руки, 2 месяца', price: 0, city: 'Нижний Новгород', area: 'Советский р-н', age: 0, verified: true, delivery: false, urgent: false },
    { id: 'l09', cat: 'electronics', title: 'MacBook Air M2, 13", 512 ГБ, на гарантии', price: 96000, city: 'Москва', area: 'Хамовники', age: 1, verified: true, delivery: true, urgent: false },
    { id: 'l10', cat: 'home', title: 'Диван-кровать, механизм еврокнижка, велюр', price: 18900, city: 'Санкт-Петербург', area: 'Приморский р-н', age: 2, verified: false, delivery: true, urgent: true },
    { id: 'l11', cat: 'transport', title: 'Электросамокат Kugoo M4 Pro, пробег 400 км', price: 27500, city: 'Казань', area: 'Ново-Савиновский', age: 1, verified: false, delivery: true, urgent: false },
    { id: 'l12', cat: 'jobs', title: 'Бариста в кофейню, сменный график, обучение', price: 62000, unit: '/мес', from: true, city: 'Москва', area: 'Китай-город', age: 0, verified: true, delivery: false, urgent: false },
    { id: 'l13', cat: 'realty', title: 'Дом 120 м² с участком 8 соток, ИЖС', price: 6400000, city: 'Краснодар', area: 'Пашковский', age: 4, verified: true, delivery: false, urgent: false },
    { id: 'l14', cat: 'personal', title: 'Пуховик зимний, размер M, носили один сезон', price: 4800, city: 'Новосибирск', area: 'Академгородок', age: 2, verified: false, delivery: true, urgent: false },
    { id: 'l15', cat: 'services', title: 'Ремонт квартир под ключ, смета за день', price: 12000, unit: '/м²', from: true, city: 'Екатеринбург', area: 'Выезд по городу', age: 1, verified: true, delivery: false, urgent: false },
    { id: 'l16', cat: 'hobby', title: 'Палатка 4-местная, дуги алюминий, тент 3000 мм', price: 9400, city: 'Нижний Новгород', area: 'Автозаводский', age: 5, verified: false, delivery: true, urgent: false },
    { id: 'l17', cat: 'electronics', title: 'Телевизор LG 55" 4K, Smart TV, 2023 год', price: 41000, city: 'Москва', area: 'Раменки', age: 3, verified: true, delivery: true, urgent: false },
    { id: 'l18', cat: 'pets', title: 'Аквариум 100 л с тумбой и фильтром', price: 7300, city: 'Санкт-Петербург', area: 'Василеостровский', age: 6, verified: false, delivery: false, urgent: false },
    { id: 'l19', cat: 'jobs', title: 'Курьер на личном авто, выплаты ежедневно', price: 95000, unit: '/мес', from: true, city: 'Казань', area: 'По всему городу', age: 1, verified: true, delivery: false, urgent: true },
    { id: 'l20', cat: 'home', title: 'Газонокосилка бензиновая, ширина скоса 46 см', price: 15600, city: 'Краснодар', area: 'Прикубанский', age: 4, verified: false, delivery: true, urgent: false }
  ];

  var CAT_LABEL = {
    transport: 'Транспорт', realty: 'Недвижимость', jobs: 'Работа', services: 'Услуги',
    personal: 'Личные вещи', home: 'Для дома и дачи', electronics: 'Электроника',
    hobby: 'Хобби и отдых', pets: 'Животные'
  };

  var CAT_PALETTE = {
    transport: ['#2b3a67', '#16203c'],
    realty: ['#3a3560', '#1d1a35'],
    jobs: ['#28405c', '#141f2f'],
    services: ['#4a3a56', '#241c2c'],
    personal: ['#4a3f2c', '#241f16'],
    home: ['#2f4a42', '#16241f'],
    electronics: ['#26364d', '#121b27'],
    hobby: ['#513243', '#271822'],
    pets: ['#4d4227', '#241f13']
  };

  /* ---------- Generated card artwork ---------- */
  var ART = {
    transport: '<path d="M22 68h56l10 14v12H22z"/><circle cx="38" cy="96" r="8"/><circle cx="80" cy="96" r="8"/><path d="M32 68V54h30v14"/>',
    realty: '<path d="M24 96V56l28-20 28 20v40z"/><path d="M44 96V74h16v22"/><path d="M14 96h92"/>',
    jobs: '<rect x="24" y="52" width="60" height="42" rx="4"/><path d="M42 52V42h24v10M24 68h60"/>',
    services: '<path d="M40 84l-14 14 8 8 14-14"/><path d="M52 70a14 14 0 1119 19l-9-9-10-10z"/>',
    personal: '<path d="M36 52h36l6 44H30z"/><path d="M46 52a8 8 0 0116 0"/>',
    home: '<path d="M28 92c0-16 10-28 26-28s26 12 26 28z"/><path d="M54 64V44M54 52c-10 0-16-6-16-14 10 0 16 6 16 14zM54 52c10 0 16-6 16-14-10 0-16 6-16 14z"/>',
    electronics: '<rect x="38" y="40" width="34" height="58" rx="6"/><path d="M50 48h10"/>',
    hobby: '<circle cx="46" cy="86" r="14"/><path d="M56 76l22-32 8 6-22 32"/>',
    pets: '<circle cx="54" cy="80" r="16"/><circle cx="38" cy="58" r="7"/><circle cx="70" cy="58" r="7"/><path d="M48 80h12"/>',
    bike: '<circle cx="32" cy="76" r="15"/><circle cx="78" cy="76" r="15"/><path d="M32 76l14-26h16l14 26M46 50h18M56 50l-8 26"/>'
  };

  var artSeq = 0;
  function thumbArt(item) {
    var pal = CAT_PALETTE[item.cat] || CAT_PALETTE.home;
    var uid = 'g' + (artSeq++);
    var seed = item.id.charCodeAt(1) + item.id.charCodeAt(2);
    var cx = 20 + (seed % 60);
    var cy = 18 + (seed % 34);
    return '' +
      '<svg viewBox="0 0 108 108" preserveAspectRatio="xMidYMid slice" aria-hidden="true">' +
        '<defs>' +
          '<linearGradient id="' + uid + '" x1="0" y1="0" x2="1" y2="1">' +
            '<stop offset="0%" stop-color="' + pal[0] + '"/>' +
            '<stop offset="100%" stop-color="' + pal[1] + '"/>' +
          '</linearGradient>' +
          '<radialGradient id="' + uid + 'r">' +
            '<stop offset="0%" stop-color="#c9a24b" stop-opacity=".45"/>' +
            '<stop offset="100%" stop-color="#c9a24b" stop-opacity="0"/>' +
          '</radialGradient>' +
        '</defs>' +
        '<rect width="108" height="108" fill="url(#' + uid + ')"/>' +
        '<circle cx="' + cx + '" cy="' + cy + '" r="46" fill="url(#' + uid + 'r)"/>' +
        '<g fill="none" stroke="#e7d5a3" stroke-opacity=".5" stroke-width="1.6" ' +
           'stroke-linecap="round" stroke-linejoin="round">' +
           (ART[item.art] || ART[item.cat] || ART.home) + '</g>' +
      '</svg>';
  }

  /* ---------- Formatting ---------- */
  var nf = new Intl.NumberFormat('ru-RU');
  function priceLabel(item) {
    if (item.price === 0) return 'Бесплатно';
    return (item.from ? 'от ' : '') + nf.format(item.price) + ' ₽' + (item.unit || '');
  }
  function ageLabel(days) {
    if (days === 0) return 'сегодня';
    if (days === 1) return 'вчера';
    var mod10 = days % 10, mod100 = days % 100;
    var word = 'дней';
    if (mod10 === 1 && mod100 !== 11) word = 'день';
    else if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) word = 'дня';
    return days + ' ' + word + ' назад';
  }

  /* ---------- Theme ---------- */
  (function themeModule() {
    var toggle = $('[data-theme-toggle]');
    if (!toggle) return;
    toggle.addEventListener('click', function () {
      var next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      document.documentElement.dataset.theme = next;
      store.set('cs-theme', next);
      toast(next === 'dark' ? 'Тёмная тема включена' : 'Светлая тема включена');
    });

    matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function (e) {
      if (store.get('cs-theme', null) === null) {
        document.documentElement.dataset.theme = e.matches ? 'dark' : 'light';
      }
    });
  })();

  /* ---------- Header state ---------- */
  (function headerModule() {
    var header = $('#siteHeader');
    if (!header) return;
    var rail = $('.catrail');

    var onScroll = function () {
      header.classList.toggle('is-stuck', window.scrollY > 8);
    };
    onScroll();
    addEventListener('scroll', onScroll, { passive: true });

    // The rail sticks directly under the header, and anchor targets must clear
    // both — so both offsets are driven by the measured header height.
    function measure() {
      var root = document.documentElement.style;
      var headerH = header.getBoundingClientRect().height;
      root.setProperty('--header-h', headerH + 'px');
      root.setProperty('--sticky-h', (headerH + (rail ? rail.getBoundingClientRect().height : 0)) + 'px');
    }
    measure();
    if ('ResizeObserver' in window) {
      var ro = new ResizeObserver(measure);
      ro.observe(header);
      if (rail) ro.observe(rail);
    } else {
      addEventListener('resize', measure);
    }
  })();

  /* ---------- Announcement bar ---------- */
  (function announceModule() {
    var bar = $('#announce');
    var btn = $('[data-dismiss-announce]');
    if (!bar || !btn) return;
    if (store.get('cs-announce-closed', false)) bar.hidden = true;
    btn.addEventListener('click', function () {
      bar.hidden = true;
      store.set('cs-announce-closed', true);
    });
  })();

  /* ---------- City picker ---------- */
  (function cityModule() {
    var toggle = $('[data-city-toggle]');
    var menu = $('[data-city-menu]');
    var label = $('[data-city-label]');
    if (!toggle || !menu) return;

    var current = store.get('cs-city', 'Москва');
    if (label) label.textContent = current;

    function sync() {
      $$('[data-city]', menu).forEach(function (b) {
        b.classList.toggle('is-active', b.textContent.trim() === current);
      });
    }
    function close() {
      menu.hidden = true;
      toggle.setAttribute('aria-expanded', 'false');
    }
    sync();

    toggle.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = menu.hidden;
      menu.hidden = !open;
      toggle.setAttribute('aria-expanded', String(open));
      if (open) sync();
    });

    menu.addEventListener('click', function (e) {
      var btn = e.target.closest('[data-city]');
      if (!btn) return;
      current = btn.textContent.trim();
      store.set('cs-city', current);
      if (label) label.textContent = current;
      close();
      toast('Город изменён: ' + current);
    });

    document.addEventListener('click', function (e) {
      if (!menu.hidden && !menu.contains(e.target) && e.target !== toggle) close();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !menu.hidden) { close(); toggle.focus(); }
    });
  })();

  /* ---------- Category rail ---------- */
  (function railModule() {
    var rail = $('[data-rail]');
    if (!rail) return;
    var prev = $('[data-rail-prev]');
    var next = $('[data-rail-next]');
    var step = function () { return Math.max(200, rail.clientWidth * 0.7); };

    if (prev) prev.addEventListener('click', function () { rail.scrollBy({ left: -step(), behavior: reduceMotion ? 'auto' : 'smooth' }); });
    if (next) next.addEventListener('click', function () { rail.scrollBy({ left: step(), behavior: reduceMotion ? 'auto' : 'smooth' }); });

    rail.addEventListener('click', function (e) {
      var chip = e.target.closest('[data-cat]');
      if (!chip) return;
      $$('.catchip', rail).forEach(function (c) { c.classList.remove('is-active'); });
      chip.classList.add('is-active');
      feed.setCategory(chip.dataset.cat);
      chip.scrollIntoView({ inline: 'center', block: 'nearest', behavior: reduceMotion ? 'auto' : 'smooth' });
    });
  })();

  /* ---------- Feed ---------- */
  var feed = (function feedModule() {
    var wrap = $('[data-feed]');
    if (!wrap) return { setCategory: function () {} };

    var stateEl = $('[data-feed-state]');
    var moreWrap = $('.feedmore');
    var moreBtn = $('[data-load-more]');
    var sortEl = $('[data-sort]');
    var favCountEl = $('[data-fav-count]');
    var favBtn = $('.fav-btn');
    var PAGE = 8;

    var state = {
      category: 'all',
      filter: 'all',
      sort: 'new',
      shown: PAGE,
      favs: store.get('cs-favs', [])
    };

    function isFav(id) { return state.favs.indexOf(id) !== -1; }

    function syncFavBadge() {
      if (favCountEl) {
        favCountEl.textContent = String(state.favs.length);
        favCountEl.hidden = state.favs.length === 0;
      }
      if (favBtn) favBtn.classList.toggle('is-active', state.favs.length > 0);
    }

    function selection() {
      var list = LISTINGS.filter(function (item) {
        if (state.category !== 'all' && item.cat !== state.category) return false;
        if (state.filter === 'verified' && !item.verified) return false;
        if (state.filter === 'delivery' && !item.delivery) return false;
        if (state.filter === 'fav' && !isFav(item.id)) return false;
        return true;
      });

      if (state.sort === 'cheap') list.sort(function (a, b) { return a.price - b.price; });
      else if (state.sort === 'expensive') list.sort(function (a, b) { return b.price - a.price; });
      else list.sort(function (a, b) { return a.age - b.age; });

      return list;
    }

    function badges(item) {
      var out = '';
      if (item.verified) out += '<span class="badge badge--verified"><svg class="ico" aria-hidden="true"><use href="#i-check"/></svg>Проверено</span>';
      if (item.urgent) out += '<span class="badge badge--urgent"><svg class="ico" aria-hidden="true"><use href="#i-bolt"/></svg>Срочно</span>';
      if (item.delivery && !item.urgent) out += '<span class="badge badge--delivery"><svg class="ico" aria-hidden="true"><use href="#i-truck"/></svg>Доставка</span>';
      return out;
    }

    function cardHTML(item, index) {
      var delay = reduceMotion ? 0 : Math.min(index, 8) * 40;
      return '' +
        '<article class="card" style="animation-delay:' + delay + 'ms">' +
          '<div class="card__media">' + thumbArt(item) +
            '<div class="card__badges">' + badges(item) + '</div>' +
            '<button class="card__fav' + (isFav(item.id) ? ' is-on' : '') + '" type="button" ' +
              'data-fav="' + item.id + '" aria-pressed="' + isFav(item.id) + '" ' +
              'aria-label="' + (isFav(item.id) ? 'Убрать из избранного' : 'Добавить в избранное') + '">' +
              '<svg class="ico" aria-hidden="true"><use href="#i-heart"/></svg>' +
            '</button>' +
          '</div>' +
          '<div class="card__body">' +
            '<p class="card__price">' + priceLabel(item) + '</p>' +
            '<h3 class="card__title">' + item.title + '</h3>' +
            '<p class="card__meta">' +
              '<span>' + item.city + ', ' + item.area + '</span>' +
              '<span class="card__dot"></span>' +
              '<span>' + ageLabel(item.age) + '</span>' +
            '</p>' +
          '</div>' +
        '</article>';
    }

    function render() {
      var list = selection();
      var page = list.slice(0, state.shown);

      wrap.innerHTML = page.map(cardHTML).join('');

      if (stateEl) {
        if (list.length === 0) {
          stateEl.hidden = false;
          stateEl.textContent = state.filter === 'fav'
            ? 'В избранном пока пусто — нажмите на сердечко у понравившегося объявления.'
            : 'По этому фильтру объявлений нет. Попробуйте другую категорию.';
        } else {
          stateEl.hidden = true;
        }
      }
      if (moreWrap) moreWrap.hidden = state.shown >= list.length;
      syncFavBadge();
    }

    wrap.addEventListener('click', function (e) {
      var btn = e.target.closest('[data-fav]');
      if (!btn) return;
      var id = btn.dataset.fav;
      var idx = state.favs.indexOf(id);
      if (idx === -1) {
        state.favs.push(id);
        toast('Добавлено в избранное');
      } else {
        state.favs.splice(idx, 1);
        toast('Убрано из избранного');
      }
      store.set('cs-favs', state.favs);

      var added = idx === -1;
      btn.classList.toggle('is-on', added);
      btn.setAttribute('aria-pressed', String(added));
      btn.setAttribute('aria-label', added ? 'Убрать из избранного' : 'Добавить в избранное');
      syncFavBadge();
      if (state.filter === 'fav') render();
    });

    if (moreBtn) {
      moreBtn.addEventListener('click', function () {
        state.shown += PAGE;
        render();
      });
    }

    if (sortEl) {
      sortEl.addEventListener('change', function () {
        state.sort = sortEl.value;
        render();
      });
    }

    $$('[data-filter]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        $$('[data-filter]').forEach(function (b) { b.classList.remove('is-active'); });
        btn.classList.add('is-active');
        state.filter = btn.dataset.filter;
        state.shown = PAGE;
        render();
      });
    });

    $$('[data-bento]').forEach(function (cell) {
      cell.addEventListener('click', function () {
        var cat = cell.dataset.bento;
        var chip = $('[data-cat="' + cat + '"]');
        if (chip) chip.click();
      });
    });

    render();

    return {
      setCategory: function (cat) {
        state.category = cat;
        state.shown = PAGE;
        render();
      },
      isFav: isFav
    };
  })();

  /* ---------- Command palette search ---------- */
  (function searchModule() {
    var overlay = $('[data-search-overlay]');
    if (!overlay) return;

    var input = $('[data-search-input]', overlay);
    var results = $('[data-search-results]', overlay);
    var label = $('[data-results-label]', overlay);
    var lastFocus = null;
    var cursor = -1;

    var isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
    $$('[data-mod-key]').forEach(function (el) { el.textContent = isMac ? '⌘' : 'Ctrl'; });

    function rowHTML(item) {
      return '' +
        '<li>' +
          '<button type="button" data-result="' + item.id + '">' +
            '<span class="cmdk__thumb">' + thumbArt(item) + '</span>' +
            '<span class="cmdk__text">' +
              '<b>' + item.title + '</b>' +
              '<small>' + (CAT_LABEL[item.cat] || '') + ' · ' + item.city + '</small>' +
            '</span>' +
            '<span class="cmdk__price">' + priceLabel(item) + '</span>' +
          '</button>' +
        '</li>';
    }

    function search(query) {
      var q = query.trim().toLowerCase();
      var list = LISTINGS;
      if (q) {
        list = LISTINGS.filter(function (item) {
          return (item.title + ' ' + item.city + ' ' + (CAT_LABEL[item.cat] || '')).toLowerCase().indexOf(q) !== -1;
        });
      }
      list = list.slice(0, 6);
      cursor = -1;

      if (label) label.textContent = q ? 'Найдено: ' + list.length : 'Из ленты';
      results.innerHTML = list.length
        ? list.map(rowHTML).join('')
        : '<li><p class="cmdk__empty">Ничего не нашлось. Попробуйте другой запрос.</p></li>';
    }

    function moveCursor(delta) {
      var rows = $$('li:has(button)', results);
      if (!rows.length) return;
      cursor = (cursor + delta + rows.length) % rows.length;
      rows.forEach(function (row, i) { row.classList.toggle('is-cursor', i === cursor); });
      rows[cursor].scrollIntoView({ block: 'nearest' });
    }

    function open() {
      lastFocus = document.activeElement;
      overlay.hidden = false;
      document.body.classList.add('is-locked');
      search('');
      requestAnimationFrame(function () { input.focus(); input.select(); });
    }

    function close() {
      overlay.hidden = true;
      document.body.classList.remove('is-locked');
      input.value = '';
      if (lastFocus && lastFocus.focus) lastFocus.focus();
    }

    $$('[data-open-search]').forEach(function (btn) { btn.addEventListener('click', open); });
    $$('[data-close-search]').forEach(function (btn) { btn.addEventListener('click', close); });

    input.addEventListener('input', function () { search(input.value); });

    $$('[data-suggest]', overlay).forEach(function (btn) {
      btn.addEventListener('click', function () {
        input.value = btn.dataset.suggest;
        search(input.value);
        input.focus();
      });
    });

    results.addEventListener('click', function (e) {
      var row = e.target.closest('[data-result]');
      if (!row) return;
      close();
      var feedEl = $('#feed');
      if (feedEl) feedEl.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
      toast('Открываем объявление');
    });

    document.addEventListener('keydown', function (e) {
      var mod = e.metaKey || e.ctrlKey;
      if (mod && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        overlay.hidden ? open() : close();
        return;
      }
      if (e.key === '/' && overlay.hidden && !/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName)) {
        e.preventDefault();
        open();
        return;
      }
      if (overlay.hidden) return;

      if (e.key === 'Escape') { e.preventDefault(); close(); }
      else if (e.key === 'ArrowDown') { e.preventDefault(); moveCursor(1); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); moveCursor(-1); }
      else if (e.key === 'Enter' && cursor > -1) {
        e.preventDefault();
        var active = $('li.is-cursor button', results);
        if (active) active.click();
      } else if (e.key === 'Tab') {
        // Keep focus inside the dialog.
        var focusables = $$('button, input, [href]', overlay).filter(function (el) { return el.offsetParent !== null; });
        if (!focusables.length) return;
        var first = focusables[0];
        var last = focusables[focusables.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    });
  })();

  /* ---------- Reveal on scroll ---------- */
  (function revealModule() {
    var nodes = $$('.reveal');
    if (!nodes.length) return;
    if (reduceMotion || !('IntersectionObserver' in window)) {
      nodes.forEach(function (n) { n.classList.add('is-in'); });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-in');
        io.unobserve(entry.target);
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.12 });
    nodes.forEach(function (n) { io.observe(n); });
  })();

  /* ---------- Animated counters ---------- */
  (function counterModule() {
    var nodes = $$('[data-count]');
    if (!nodes.length) return;

    function run(el) {
      var target = parseFloat(el.dataset.count);
      var decimals = parseInt(el.dataset.decimals || '0', 10);
      if (reduceMotion) {
        el.textContent = target.toFixed(decimals).replace('.', ',');
        return;
      }
      var duration = 1100;
      var start = performance.now();
      (function tick(now) {
        var p = Math.min(1, (now - start) / duration);
        var eased = 1 - Math.pow(1 - p, 3);
        el.textContent = (target * eased).toFixed(decimals).replace('.', ',');
        if (p < 1) requestAnimationFrame(tick);
      })(start);
    }

    if (!('IntersectionObserver' in window)) { nodes.forEach(run); return; }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        run(entry.target);
        io.unobserve(entry.target);
      });
    }, { threshold: 0.6 });
    nodes.forEach(function (n) { io.observe(n); });
  })();

  /* ---------- Hero ticker ---------- */
  (function tickerModule() {
    var line = $('[data-ticker-line]');
    if (!line || reduceMotion) return;
    var pool = LISTINGS.slice();
    var i = 0;
    setInterval(function () {
      i = (i + 1) % pool.length;
      var item = pool[i];
      line.style.animation = 'none';
      void line.offsetWidth;
      line.style.animation = '';
      line.textContent = item.title.split(',')[0] + ' — ' + item.city;
    }, 3200);
  })();

  /* ---------- Signup form ---------- */
  (function signupModule() {
    var form = $('[data-signup]');
    if (!form) return;
    var hint = $('[data-signup-hint]', form);
    var input = form.querySelector('input[type="email"]');
    var defaultHint = hint ? hint.textContent : '';

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var value = input.value.trim();
      var valid = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(value);

      if (!hint) return;
      hint.classList.remove('is-error', 'is-success');

      if (!valid) {
        hint.textContent = 'Проверьте адрес — похоже, в нём опечатка.';
        hint.classList.add('is-error');
        input.focus();
        return;
      }
      hint.textContent = 'Готово! Чек-лист отправлен на ' + value + '.';
      hint.classList.add('is-success');
      form.reset();
      toast('Чек-лист продавца отправлен');
      setTimeout(function () {
        hint.textContent = defaultHint;
        hint.classList.remove('is-success');
      }, 6000);
    });
  })();

  /* ---------- Toasts ---------- */
  function toast(message) {
    var host = $('[data-toasts]');
    if (!host) return;
    var el = document.createElement('div');
    el.className = 'toast';
    el.innerHTML = '<svg class="ico ico--xs" aria-hidden="true"><use href="#i-check"/></svg><span></span>';
    el.querySelector('span').textContent = message;
    host.appendChild(el);

    setTimeout(function () {
      el.classList.add('is-leaving');
      el.addEventListener('animationend', function () { el.remove(); }, { once: true });
      setTimeout(function () { if (el.isConnected) el.remove(); }, 600);
    }, 2400);
  }
})();
