(function () {
  var view = document.getElementById('view');
  var modal = document.getElementById('modal');
  var modalTitle = document.getElementById('modal-title');
  var modalBody = document.getElementById('modal-body');
  var modalClose = document.getElementById('modal-close');
  var navToggle = document.getElementById('nav-toggle');
  var navBackdrop = document.getElementById('nav-backdrop');
  var topbarKicker = document.getElementById('topbar-kicker');
  var topbarTitle = document.getElementById('topbar-title');
  var refreshTimer = null;
  var packetFeedbackTimer = null;
  var state = {
    mappings: [],
    connectionPage: 1,
    packetPage: 1,
    connectionLoading: false,
    packetLoading: false,
    packetDetail: null,
    pendingPacketFilters: null,
    mappingActionPending: {},
    mappingEditor: defaultMappingEditorState(),
    packetPurge: defaultPacketPurgeState(),
    confirmDialog: defaultConfirmDialogState(),
    modalType: null,
    modalReturnFocus: null
  };
  var ROUTE_META = {
    '/': { kicker: '运行巡检', title: '运行状态' },
    '/mappings': { kicker: '映射维护', title: '映射配置' },
    '/connections': { kicker: '连接排查', title: '连接记录' },
    '/packets': { kicker: '报文分析', title: '报文记录' }
  };
  var PAGE_COPY = {
    runtime: {
      title: '运行状态',
      subtitle: '查看映射运行情况、抓包写入状态和最近异常，适合巡检时快速确认系统健康度。'
    },
    mappings: {
      title: '映射配置',
      subtitle: '新建、修改、启停转发映射。变更会立即影响对应监听端口和转发行为。'
    },
    connections: {
      title: '连接记录',
      subtitle: '按映射、客户端和时间范围筛选连接，查看生命周期并继续追踪相关报文。'
    },
    packets: {
      title: '报文记录',
      subtitle: '按连接、映射和时间范围筛选已捕获报文，查看内容预览并按范围清理历史数据。'
    }
  };
  var FILTER_TIME_PLACEHOLDER = '2026-04-17 10:30:00';
  var FIELD_LABELS = {
    mappingId: '映射 ID',
    clientIp: '客户端 IP',
    clientPort: '客户端端口',
    listenIp: '监听 IP',
    listenPort: '监听端口',
    forwardHost: '转发主机',
    forwardPort: '转发端口',
    remoteIp: '远端 IP',
    remotePort: '远端端口',
    status: '状态',
    closeReason: '关闭原因',
    openedAt: '打开时间',
    closedAt: '关闭时间',
    bytesUp: '上行字节',
    bytesDown: '下行字节',
    errorMessage: '错误信息',
    direction: '方向',
    sequenceNo: '序号',
    protocolFamily: '协议族',
    applicationProtocol: '应用协议',
    contentType: '内容类型',
    httpMethod: 'HTTP 方法',
    httpUri: 'HTTP 路径',
    httpStatus: 'HTTP 状态码',
    targetHost: '目标主机',
    targetPort: '目标端口',
    payloadSize: '原始大小',
    capturedSize: '捕获大小',
    truncated: '是否截断',
    receivedAt: '接收时间',
    connectionId: '连接 ID'
  };

  function translateStatus(status) {
    var labels = {
      RUNNING: '运行中',
      STOPPED: '已停止',
      FAILED: '失败',
      STARTING: '启动中',
      STOPPING: '停止中',
      OPENING: '连接中',
      OPEN: '已连接',
      CLOSED: '已关闭',
      ERROR: '错误'
    };
    return labels[status] || status || '-';
  }

  function translateDirection(direction) {
    var labels = {
      REQUEST: '请求',
      RESPONSE: '响应'
    };
    return labels[direction] || direction || '-';
  }

  function translateProtocol(protocol) {
    var normalized = String(protocol || '').toLowerCase();
    var labels = {
      tcp: 'TCP',
      http: 'HTTP',
      http1: 'HTTP/1.1',
      https: 'HTTPS',
      tls: 'TLS'
    };
    return labels[normalized] || protocol || '-';
  }

  function translateBoolean(value) {
    if (value === true) {
      return '是';
    }
    if (value === false) {
      return '否';
    }
    return value == null ? '-' : value;
  }

  function translateFieldLabel(key) {
    return FIELD_LABELS[key] || key;
  }

  function translateOptionLabel(value) {
    if (!value) {
      return '不限';
    }
    return translateStatus(value) !== value ? translateStatus(value) : translateDirection(value);
  }

  function api(path, options) {
    var request = options || {};
    request.headers = request.headers || {};
    if (request.body && !request.headers['Content-Type']) {
      request.headers['Content-Type'] = 'application/json';
    }
    return fetch(path, request).then(function (response) {
      if (response.status === 401) {
        window.location.href = '/login';
        return Promise.reject(new Error('请先登录'));
      }
      return response.json().then(function (body) {
        if (!body.success) {
          var message = body.error && body.error.message ? body.error.message : '请求失败';
          var error = new Error(message);
          error.code = body.error && body.error.code;
          throw error;
        }
        return body.data || {};
      });
    });
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function qs(params) {
    var parts = [];
    Object.keys(params).forEach(function (key) {
      var value = params[key];
      if (value !== undefined && value !== null && String(value).trim() !== '') {
        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
      }
    });
    return parts.length ? '?' + parts.join('&') : '';
  }

  function setActiveNav() {
    var path = window.location.pathname;
    Array.prototype.forEach.call(document.querySelectorAll('[data-nav]'), function (link) {
      link.classList.toggle('active', link.getAttribute('data-nav') === path);
    });
    updateTopbar(path);
  }

  function updateTopbar(path) {
    var meta = ROUTE_META[path] || ROUTE_META['/'];
    if (topbarKicker) {
      topbarKicker.textContent = meta.kicker;
    }
    if (topbarTitle) {
      topbarTitle.textContent = meta.title;
    }
  }

  function toggleNav(forceOpen) {
    var open = typeof forceOpen === 'boolean' ? forceOpen : !document.body.classList.contains('nav-open');
    document.body.classList.toggle('nav-open', open);
    if (navToggle) {
      navToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    }
  }

  function setError(target, error) {
    setMessage(target, error && error.message ? error.message : '', 'error');
  }

  function setMessage(target, message, tone) {
    var node = document.getElementById(target);
    if (node) {
      node.textContent = message || '';
      node.classList.remove('success');
      if (tone === 'success') {
        node.classList.add('success');
      }
    }
  }

  function defaultMappingEditorState() {
    return {
      open: false,
      mode: 'create',
      mappingId: null,
      submitting: false,
      dirty: false,
      initialValue: '',
      draftValue: null
    };
  }

  function defaultPacketPurgeState() {
    return {
      open: false,
      step: 'select',
      scope: null,
      submitting: false,
      error: null
    };
  }

  function defaultConfirmDialogState() {
    return {
      open: false,
      title: '',
      description: '',
      confirmLabel: '确认',
      cancelLabel: '取消',
      tone: 'default',
      action: null,
      submitting: false,
      error: null,
      returnTo: null
    };
  }

  function statusBadge(status) {
    var value = String(status || '').toLowerCase();
    return '<span class="status ' + escapeHtml(value) + '">' + escapeHtml(translateStatus(status)) + '</span>';
  }

  function metric(label, value, tone) {
    return '<div class="metric' + (tone ? ' metric-' + escapeHtml(tone) : '') + '"><div class="label">' + escapeHtml(label) + '</div><div class="value">' + escapeHtml(value) + '</div></div>';
  }

  function pageHead(title, subtitle, action, meta) {
    return '<section class="page-hero"><div class="page-intro"><h1>' + escapeHtml(title) + '</h1><p class="page-copy">' + escapeHtml(subtitle || '') + '</p>' +
      (meta ? '<div class="page-context-meta">' + meta + '</div>' : '') +
      '</div>' + (action ? '<div class="hero-actions">' + action + '</div>' : '') + '</section>';
  }

  function heroBadge(text) {
    return '<span class="hero-badge">' + escapeHtml(text) + '</span>';
  }

  function badgeGroup(items) {
    return (items || []).filter(function (item) {
      return !!item;
    }).map(heroBadge).join('');
  }

  function emptyTableRow(colspan, message) {
    return '<tr><td colspan="' + colspan + '" class="table-empty">' + escapeHtml(message) + '</td></tr>';
  }

  function renderRuntime() {
    clearTimer();
    view.innerHTML = pageHead(PAGE_COPY.runtime.title, PAGE_COPY.runtime.subtitle, '', '<div id="runtime-context-meta">' + badgeGroup(['每 3 秒自动刷新']) + '</div>') +
      '<div id="runtime-alert"></div><div id="runtime-metrics" class="grid metrics"></div>';
    function load() {
      api('/api/runtime').then(function (data) {
        var alertNode = document.getElementById('runtime-alert');
        var contextMeta = document.getElementById('runtime-context-meta');
        var issues = [];
        if (data.failedMappings) {
          issues.push(String(data.failedMappings) + ' 条映射启动失败');
        }
        if (data.packetsDropped) {
          issues.push('已有 ' + String(data.packetsDropped) + ' 条报文被丢弃');
        }
        if (data.lastWriterError) {
          issues.push('最近写入错误：' + data.lastWriterError);
        }
        if (contextMeta) {
          contextMeta.innerHTML = badgeGroup([
            '每 3 秒自动刷新',
            '共 ' + String(data.mappings || 0) + ' 条映射',
            '当前 ' + String(data.activeConnections || 0) + ' 条活跃连接'
          ]);
        }
        if (alertNode) {
          alertNode.innerHTML = issues.length
            ? '<div class="page-inline-alert">' + escapeHtml(issues.join('；')) + '</div>'
            : '';
        }
        document.getElementById('runtime-metrics').innerHTML = [
          metric('映射总数', data.mappings),
          metric('运行中', data.runningMappings),
          metric('失败', data.failedMappings, data.failedMappings ? 'danger' : ''),
          metric('写入错误', data.lastWriterError || '-', data.lastWriterError ? 'danger' : ''),
          metric('已丢弃报文', data.packetsDropped, data.packetsDropped ? 'danger' : ''),
          metric('已停止', data.stoppedMappings, data.stoppedMappings ? 'warn' : ''),
          metric('活跃连接', data.activeConnections),
          metric('抓包队列', data.captureQueueSize + ' / ' + data.captureQueueCapacity),
          metric('已写入报文', data.packetsWritten),
          metric('已落盘报文', data.packetsSpilled),
          metric('落盘文件', data.spillFileCount),
          metric('落盘字节', data.spillBytes),
          metric('Payload 段文件', data.payloadFilesActive),
          metric('Payload 占用', data.payloadBytesOnDisk)
        ].join('');
      }).catch(function (error) {
        view.innerHTML = pageHead(PAGE_COPY.runtime.title, '') + '<p class="error-text">' + escapeHtml(error.message) + '</p>';
      });
    }
    load();
    refreshTimer = window.setInterval(load, 3000);
  }

  function renderMappings() {
    clearTimer();
    view.innerHTML = pageHead(PAGE_COPY.mappings.title, PAGE_COPY.mappings.subtitle, '<button id="mapping-create" type="button">新建映射</button>') +
      '<section class="panel"><div class="panel-head"><h2>映射列表</h2><button id="mapping-refresh" class="compact-button" type="button">刷新</button></div>' +
      '<div class="panel-body panel-stack"><div class="table-wrap"><table><thead><tr><th>ID</th><th>名称</th><th>监听</th><th>转发</th><th>状态</th><th>连接数</th><th>最近错误</th><th></th></tr></thead><tbody id="mapping-rows"></tbody></table></div>' +
      '<p id="mapping-message" class="form-message"></p></div></section>';
    document.getElementById('mapping-create').addEventListener('click', function () {
      openMappingModal('create');
    });
    document.getElementById('mapping-refresh').addEventListener('click', loadMappings);
    loadMappings();
  }

  function loadMappings() {
    api('/api/mappings').then(function (items) {
      state.mappings = items || [];
      renderMappingRows();
      if (state.mappingEditor.open && state.mappingEditor.mode === 'edit') {
        var current = findMappingById(state.mappingEditor.mappingId);
        if (current) {
          fillMappingForm(current);
        }
      }
    }).catch(function (error) {
      setError('mapping-message', error);
    });
  }

  function renderMappingRows() {
    var rows = document.getElementById('mapping-rows');
    if (!rows) {
      return;
    }
    rows.innerHTML = state.mappings.length ? state.mappings.map(function (item) {
      return '<tr>' +
        '<td>' + item.id + '</td>' +
        '<td>' + escapeHtml(item.name) + '<div class="muted">' + (item.enabled ? '已启用' : '已禁用') + '</div></td>' +
        '<td>' + item.listenPort + '<div class="muted">' + escapeHtml(translateProtocol(item.listenMode || 'tcp')) + '</div></td>' +
        '<td>' + escapeHtml(item.forwardHost) + ':' + item.forwardPort + '<div class="muted">' + escapeHtml(translateProtocol(item.forwardMode || 'tcp')) + '</div></td>' +
        '<td>' + statusBadge(item.status) + '</td>' +
        '<td>' + item.activeConnections + '</td>' +
        '<td class="error-text">' + escapeHtml(item.lastError || '') + '</td>' +
        '<td class="actions">' + renderMappingActionButtons(item) + '</td></tr>';
    }).join('') : emptyTableRow(8, '当前还没有映射。先新建一条映射，再开始监听和转发。');
    bindMappingActions();
  }

  function renderMappingActionButtons(item) {
    var pendingAction = state.mappingActionPending[item.id];
    var allDisabled = !!pendingAction;
    var action = mappingPrimaryAction(item, pendingAction);
    return [
      mappingButtonHtml('edit', item.id, '编辑', 'compact-button', allDisabled),
      mappingButtonHtml(action.action, item.id, action.label, 'compact-button', allDisabled || action.disabled),
      mappingButtonHtml('delete', item.id, '删除', 'danger-button', allDisabled)
    ].join('');
  }

  function mappingPrimaryAction(item, pendingAction) {
    if (pendingAction === 'start') {
      return { action: 'start', label: '启动中', disabled: true };
    }
    if (pendingAction === 'stop') {
      return { action: 'stop', label: '停止中', disabled: true };
    }
    if (item.status === 'RUNNING') {
      return { action: 'stop', label: '停止', disabled: false };
    }
    if (item.status === 'FAILED') {
      return { action: 'start', label: '重试启动', disabled: false };
    }
    if (item.status === 'STARTING') {
      return { action: 'start', label: '启动中', disabled: true };
    }
    if (item.status === 'STOPPING') {
      return { action: 'stop', label: '停止中', disabled: true };
    }
    return { action: 'start', label: '启动', disabled: false };
  }

  function mappingButtonHtml(action, id, label, className, disabled) {
    return '<button class="' + className + '" type="button" data-mapping-action="' + escapeHtml(action) + '" data-mapping-id="' + id + '"' +
      (disabled ? ' disabled' : '') + '>' + escapeHtml(label) + '</button>';
  }

  function bindMappingActions() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-mapping-action]'), function (button) {
      button.addEventListener('click', function () {
        var id = Number(button.getAttribute('data-mapping-id'));
        var action = button.getAttribute('data-mapping-action');
        if (action === 'edit') {
          editMapping(id);
          return;
        }
        if (action === 'delete') {
          openConfirmDialog({
            title: '删除映射 #' + id,
            description: '删除后会移除这条转发规则，新的入站连接将不再按该映射处理。已建立连接是否中断取决于服务端当前状态。',
            confirmLabel: '删除映射',
            tone: 'danger',
            action: { type: 'delete-mapping', mappingId: id }
          });
          return;
        }
        mappingAction(id, action);
      });
    });
  }

  function editMapping(id) {
    openMappingModal('edit', findMappingById(id));
  }

  function findMappingById(id) {
    return state.mappings.filter(function (mapping) { return mapping.id === id; })[0];
  }

  function openMappingModal(mode, item) {
    if (mode === 'edit' && !item) {
      setMessage('mapping-message', '映射不存在或已被删除', 'error');
      return;
    }
    state.mappingEditor = defaultMappingEditorState();
    state.mappingEditor.open = true;
    state.mappingEditor.mode = mode === 'edit' ? 'edit' : 'create';
    state.mappingEditor.mappingId = item && item.id != null ? item.id : null;
    modal.classList.add('modal-mapping-editor');
    openModal('mapping-editor');
    renderMappingModal();
    if (state.mappingEditor.mode === 'edit' && item) {
      fillMappingForm(item);
    } else {
      resetMappingForm('create');
    }
  }

  function renderMappingModal() {
    var editing = state.mappingEditor.mode === 'edit';
    modalTitle.textContent = editing ? '编辑映射 #' + state.mappingEditor.mappingId : '新建映射';
    modal.setAttribute('aria-describedby', 'mapping-dialog-description');
    modalBody.innerHTML =
      '<div class="modal-body-shell">' +
      '<p id="mapping-dialog-description" class="dialog-description">维护监听端口、转发目标和协议选项。保存后会立即影响对应映射的运行行为。</p>' +
      '<form id="mapping-form" class="form-grid">' +
      '<input type="hidden" id="mapping-id">' +
      '<section class="dialog-section"><div class="dialog-section-head"><h3>基本信息</h3><p>用于标识这条映射，便于后续巡检和排障。</p></div>' +
      '<div class="form-grid"><div><label>名称</label><input id="mapping-name" required></div>' +
      '<label class="check-row"><input id="mapping-enabled" type="checkbox" checked> 启用这条映射</label></div></section>' +
      '<section class="dialog-section"><div class="dialog-section-head"><h3>监听配置</h3><p>修改监听端口会影响新的入站连接；协议切换会改变当前监听端的解析方式。</p></div>' +
      '<div class="form-grid"><div><label>监听端口</label><input id="mapping-listen-port" type="number" min="0" max="65535" required></div>' +
      '<div><label>监听协议</label><select id="mapping-listen-protocol"><option value="tcp">tcp</option><option value="http">http</option></select></div>' +
      '<label class="check-row"><input id="mapping-listen-tls-enabled" type="checkbox"> 启用监听 TLS</label>' +
      '<p id="mapping-listen-tls-hint" class="field-hint"></p>' +
      '<div id="mapping-listen-tls-fields">' +
      '<div><label>监听证书文件</label><input id="mapping-listen-cert-file"></div>' +
      '<div><label>监听私钥文件</label><input id="mapping-listen-key-file"></div>' +
      '<div><label>监听私钥密码</label><input id="mapping-listen-key-password" type="password"></div>' +
      '</div></div></section>' +
      '<section class="dialog-section"><div class="dialog-section-head"><h3>转发配置</h3><p>定义后端目标和出站协议。开启 TLS 后可以指定 SNI 和证书校验策略。</p></div>' +
      '<div class="form-grid">' +
      '<div><label>转发主机</label><input id="mapping-forward-host" required></div>' +
      '<div><label>转发端口</label><input id="mapping-forward-port" type="number" min="0" max="65535" required></div>' +
      '<div><label>转发协议</label><select id="mapping-forward-protocol"><option value="tcp">tcp</option><option value="http">http</option></select></div>' +
      '<label class="check-row"><input id="mapping-forward-tls-enabled" type="checkbox"> 启用转发 TLS</label>' +
      '<p id="mapping-forward-tls-hint" class="field-hint"></p>' +
      '<div id="mapping-forward-tls-fields">' +
      '<div><label>转发 SNI 主机</label><input id="mapping-forward-sni-host"></div>' +
      '<label class="check-row"><input id="mapping-forward-insecure" type="checkbox"> 跳过证书校验</label>' +
      '<p class="field-hint">仅建议测试环境使用，生产环境更适合提供受信任证书链。</p>' +
      '<div><label>转发信任证书文件</label><input id="mapping-forward-trust-cert"></div>' +
      '</div>' +
      '</div></section>' +
      '<section id="mapping-http-fields" class="dialog-section">' +
      '<div class="dialog-section-head"><h3>HTTP 高级选项</h3><p>仅在监听端或转发端使用 HTTP 协议时生效。</p></div>' +
      '<div class="form-grid">' +
      '<label class="check-row"><input id="mapping-http-rewrite-host" type="checkbox" checked> 重写 Host</label>' +
      '<label class="check-row"><input id="mapping-http-add-x-forwarded" type="checkbox" checked> 添加 X-Forwarded 头</label>' +
      '<div><label>HTTP 最大对象大小</label><input id="mapping-http-max-object-size" type="number" min="1" value="1048576"></div>' +
      '</div></section>' +
      '<p id="mapping-modal-message" class="form-message"></p>' +
      '<div class="modal-actions">' +
      '<button id="mapping-cancel" class="secondary-button" type="button"' + (state.mappingEditor.submitting ? ' disabled' : '') + '>取消</button>' +
      '<button type="submit"' + (state.mappingEditor.submitting ? ' disabled' : '') + '>保存</button>' +
      '</div>' +
      '</form></div>';
    setModalDismissState(state.mappingEditor.submitting);
    document.getElementById('mapping-form').addEventListener('submit', saveMapping);
    document.getElementById('mapping-cancel').addEventListener('click', function () {
      closeModal();
    });
    bindMappingFormToggles();
    bindMappingFormDirtyState();
    syncMappingFormVisibility();
    focusModalElement('#mapping-name');
  }

  function fillMappingForm(item) {
    if (!item) {
      return;
    }
    var listen = item.listen || {};
    var forward = item.forward || {};
    var listenTls = listen.tls || {};
    var forwardTls = forward.tls || {};
    var http = item.http || {};
    modalTitle.textContent = '编辑映射 #' + item.id;
    document.getElementById('mapping-id').value = item.id;
    document.getElementById('mapping-name').value = item.name || '';
    document.getElementById('mapping-listen-port').value = item.listenPort;
    document.getElementById('mapping-listen-protocol').value = listen.applicationProtocol || 'tcp';
    document.getElementById('mapping-listen-tls-enabled').checked = !!listenTls.enabled;
    document.getElementById('mapping-listen-cert-file').value = listenTls.certificateFile || '';
    document.getElementById('mapping-listen-key-file').value = listenTls.privateKeyFile || '';
    document.getElementById('mapping-listen-key-password').value = listenTls.privateKeyPassword || '';
    document.getElementById('mapping-forward-host').value = item.forwardHost || '';
    document.getElementById('mapping-forward-port').value = item.forwardPort;
    document.getElementById('mapping-forward-protocol').value = forward.applicationProtocol || 'tcp';
    document.getElementById('mapping-forward-tls-enabled').checked = !!forwardTls.enabled;
    document.getElementById('mapping-forward-sni-host').value = forwardTls.sniHost || '';
    document.getElementById('mapping-forward-insecure').checked = !!forwardTls.insecureSkipVerify;
    document.getElementById('mapping-forward-trust-cert').value = forwardTls.trustCertCollectionFile || '';
    document.getElementById('mapping-http-rewrite-host').checked = http.rewriteHost !== false;
    document.getElementById('mapping-http-add-x-forwarded').checked = http.addXForwardedHeaders !== false;
    document.getElementById('mapping-http-max-object-size').value = http.maxObjectSizeBytes || 1048576;
    document.getElementById('mapping-enabled').checked = !!item.enabled;
    syncMappingFormVisibility();
    snapshotMappingEditorState();
  }

  function resetMappingForm(mode) {
    document.getElementById('mapping-form').reset();
    document.getElementById('mapping-id').value = '';
    document.getElementById('mapping-listen-protocol').value = 'tcp';
    document.getElementById('mapping-forward-protocol').value = 'tcp';
    document.getElementById('mapping-enabled').checked = true;
    document.getElementById('mapping-http-rewrite-host').checked = true;
    document.getElementById('mapping-http-add-x-forwarded').checked = true;
    document.getElementById('mapping-http-max-object-size').value = 1048576;
    syncMappingFormVisibility();
    setMessage('mapping-modal-message', '', 'error');
    state.mappingEditor.mode = mode || 'create';
    state.mappingEditor.mappingId = null;
    modalTitle.textContent = '新建映射';
    snapshotMappingEditorState();
  }

  function readMappingForm() {
    var listenProtocol = valueOf('mapping-listen-protocol') || 'tcp';
    var forwardProtocol = valueOf('mapping-forward-protocol') || 'tcp';
    var listenPort = Number(document.getElementById('mapping-listen-port').value);
    var forwardHost = document.getElementById('mapping-forward-host').value.trim();
    var forwardPort = Number(document.getElementById('mapping-forward-port').value);
    return {
      name: document.getElementById('mapping-name').value.trim(),
      enabled: document.getElementById('mapping-enabled').checked,
      listenPort: listenPort,
      forwardHost: forwardHost,
      forwardPort: forwardPort,
      listen: {
        port: listenPort,
        applicationProtocol: listenProtocol,
        tls: {
          enabled: document.getElementById('mapping-listen-tls-enabled').checked,
          certificateFile: valueOf('mapping-listen-cert-file') || null,
          privateKeyFile: valueOf('mapping-listen-key-file') || null,
          privateKeyPassword: valueOf('mapping-listen-key-password') || null
        }
      },
      forward: {
        host: forwardHost,
        port: forwardPort,
        applicationProtocol: forwardProtocol,
        tls: {
          enabled: document.getElementById('mapping-forward-tls-enabled').checked,
          sniHost: valueOf('mapping-forward-sni-host') || null,
          insecureSkipVerify: document.getElementById('mapping-forward-insecure').checked,
          trustCertCollectionFile: valueOf('mapping-forward-trust-cert') || null
        }
      },
      http: {
        rewriteHost: document.getElementById('mapping-http-rewrite-host').checked,
        addXForwardedHeaders: document.getElementById('mapping-http-add-x-forwarded').checked,
        maxObjectSizeBytes: Number(document.getElementById('mapping-http-max-object-size').value || '1048576')
      }
    };
  }

  function bindMappingFormToggles() {
    ['mapping-listen-protocol', 'mapping-forward-protocol', 'mapping-listen-tls-enabled', 'mapping-forward-tls-enabled'].forEach(function (id) {
      document.getElementById(id).addEventListener('change', syncMappingFormVisibility);
    });
  }

  function bindMappingFormDirtyState() {
    var form = document.getElementById('mapping-form');
    if (!form) {
      return;
    }
    form.addEventListener('input', updateMappingEditorDirtyState);
    form.addEventListener('change', updateMappingEditorDirtyState);
  }

  function snapshotMappingEditorState() {
    state.mappingEditor.initialValue = JSON.stringify(readMappingForm());
    state.mappingEditor.dirty = false;
  }

  function updateMappingEditorDirtyState() {
    if (!state.mappingEditor.open) {
      return;
    }
    state.mappingEditor.dirty = JSON.stringify(readMappingForm()) !== state.mappingEditor.initialValue;
  }

  function syncMappingFormVisibility() {
    var listenProtocol = valueOf('mapping-listen-protocol') || 'tcp';
    var forwardProtocol = valueOf('mapping-forward-protocol') || 'tcp';
    var isHttp = listenProtocol === 'http' || forwardProtocol === 'http';
    var listenTlsEnabled = document.getElementById('mapping-listen-tls-enabled').checked;
    var forwardTlsEnabled = document.getElementById('mapping-forward-tls-enabled').checked;
    document.getElementById('mapping-http-fields').hidden = !isHttp;
    document.getElementById('mapping-listen-tls-fields').hidden = !listenTlsEnabled;
    document.getElementById('mapping-forward-tls-fields').hidden = !forwardTlsEnabled;
    document.getElementById('mapping-listen-tls-hint').textContent = listenTlsEnabled
      ? '请提供监听端使用的证书和私钥文件。'
      : '如需在监听端终止 TLS，请开启后补充证书文件。';
    document.getElementById('mapping-forward-tls-hint').textContent = forwardTlsEnabled
      ? '将以 TLS 方式连接后端，可继续配置 SNI 和证书校验策略。'
      : '后端使用明文 TCP 连接；如目标服务要求 TLS，请在这里开启。';
  }

  function saveMapping(event) {
    event.preventDefault();
    var id = document.getElementById('mapping-id').value;
    var path = id ? '/api/mappings/' + id : '/api/mappings';
    var method = id ? 'PUT' : 'POST';
    var formData = readMappingForm();
    if (id) {
      state.mappingActionPending[id] = 'save';
      renderMappingRows();
    }
    state.mappingEditor.submitting = true;
    renderMappingModal();
    restoreMappingFormValues(formData);
    api(path, { method: method, body: JSON.stringify(formData) }).then(function () {
      state.mappingEditor.submitting = false;
      state.mappingEditor.dirty = false;
      delete state.mappingActionPending[id];
      closeModal(true);
      loadMappings();
    }).catch(function (error) {
      state.mappingEditor.submitting = false;
      delete state.mappingActionPending[id];
      renderMappingRows();
      renderMappingModal();
      restoreMappingFormValues(formData);
      state.mappingEditor.dirty = JSON.stringify(formData) !== state.mappingEditor.initialValue;
      setError('mapping-modal-message', error);
    });
  }

  function mappingAction(id, action) {
    state.mappingActionPending[id] = action;
    renderMappingRows();
    var request = action === 'delete'
      ? api('/api/mappings/' + id, { method: 'DELETE' })
      : api('/api/mappings/' + id + '/' + action, { method: 'POST' });
    return request.then(function () {
      delete state.mappingActionPending[id];
      loadMappings();
    }).catch(function (error) {
      delete state.mappingActionPending[id];
      renderMappingRows();
      setError('mapping-message', error);
      throw error;
    });
  }

  function restoreMappingFormValues(data) {
    if (!data) {
      return;
    }
    document.getElementById('mapping-id').value = state.mappingEditor.mappingId || '';
    document.getElementById('mapping-name').value = data.name || '';
    document.getElementById('mapping-listen-port').value = data.listenPort;
    document.getElementById('mapping-listen-protocol').value = data.listen && data.listen.applicationProtocol ? data.listen.applicationProtocol : 'tcp';
    document.getElementById('mapping-listen-tls-enabled').checked = !!(data.listen && data.listen.tls && data.listen.tls.enabled);
    document.getElementById('mapping-listen-cert-file').value = data.listen && data.listen.tls && data.listen.tls.certificateFile || '';
    document.getElementById('mapping-listen-key-file').value = data.listen && data.listen.tls && data.listen.tls.privateKeyFile || '';
    document.getElementById('mapping-listen-key-password').value = data.listen && data.listen.tls && data.listen.tls.privateKeyPassword || '';
    document.getElementById('mapping-forward-host').value = data.forwardHost || '';
    document.getElementById('mapping-forward-port').value = data.forwardPort;
    document.getElementById('mapping-forward-protocol').value = data.forward && data.forward.applicationProtocol ? data.forward.applicationProtocol : 'tcp';
    document.getElementById('mapping-forward-tls-enabled').checked = !!(data.forward && data.forward.tls && data.forward.tls.enabled);
    document.getElementById('mapping-forward-sni-host').value = data.forward && data.forward.tls && data.forward.tls.sniHost || '';
    document.getElementById('mapping-forward-insecure').checked = !!(data.forward && data.forward.tls && data.forward.tls.insecureSkipVerify);
    document.getElementById('mapping-forward-trust-cert').value = data.forward && data.forward.tls && data.forward.tls.trustCertCollectionFile || '';
    document.getElementById('mapping-http-rewrite-host').checked = !!(data.http ? data.http.rewriteHost : true);
    document.getElementById('mapping-http-add-x-forwarded').checked = !!(data.http ? data.http.addXForwardedHeaders : true);
    document.getElementById('mapping-http-max-object-size').value = data.http && data.http.maxObjectSizeBytes ? data.http.maxObjectSizeBytes : 1048576;
    document.getElementById('mapping-enabled').checked = !!data.enabled;
    syncMappingFormVisibility();
  }

  function renderConnections() {
    clearTimer();
    view.innerHTML = pageHead(PAGE_COPY.connections.title, PAGE_COPY.connections.subtitle) +
      '<section class="panel"><form id="connection-filter-form" class="panel-body panel-stack filter-form">' +
      '<div class="filter-form-grid">' +
      field('connection-mapping-id', '映射 ID', '例如 7') +
      field('connection-client-ip', '客户端 IP', '例如 10.0.0.8') +
      selectField('connection-status', '状态', ['', 'OPENING', 'OPEN', 'CLOSED', 'FAILED']) +
      timeField('connection-from', '开始时间') +
      timeField('connection-to', '结束时间') +
      '</div>' +
      '<div class="filter-form-actions"><button id="connection-search" type="submit">查询</button><button id="connection-reset" class="secondary-button" type="reset">重置</button></div>' +
      '<div class="table-wrap"><table><thead><tr><th>ID</th><th>映射</th><th>客户端</th><th>监听</th><th>目标</th><th>状态</th><th>打开时间</th><th>字节数</th><th></th></tr></thead><tbody id="connection-rows"></tbody></table></div>' +
      '<div class="pager"><button id="connection-prev" class="secondary-button" type="button">上一页</button><span id="connection-page-info"></span><button id="connection-next" class="secondary-button" type="button">下一页</button></div>' +
      '<p id="connection-message" class="form-message"></p></form></section>';
    document.getElementById('connection-filter-form').addEventListener('submit', function (event) {
      event.preventDefault();
      state.connectionPage = 1;
      loadConnections();
    });
    document.getElementById('connection-filter-form').addEventListener('reset', function () {
      window.setTimeout(function () {
        state.connectionPage = 1;
        setMessage('connection-message', '', 'error');
        loadConnections();
      }, 0);
    });
    document.getElementById('connection-prev').addEventListener('click', function () {
      state.connectionPage = Math.max(1, state.connectionPage - 1);
      loadConnections();
    });
    document.getElementById('connection-next').addEventListener('click', function () {
      state.connectionPage += 1;
      loadConnections();
    });
    loadConnections();
  }

  function loadConnections() {
    state.connectionLoading = true;
    setFilterLoadingState('connection', true);
    api('/api/connections' + qs({
      mappingId: valueOf('connection-mapping-id'),
      clientIp: valueOf('connection-client-ip'),
      status: valueOf('connection-status'),
      from: valueOf('connection-from'),
      to: valueOf('connection-to'),
      page: state.connectionPage,
      pageSize: 50
    })).then(function (page) {
      var rows = page.items || [];
      document.getElementById('connection-rows').innerHTML = rows.length ? rows.map(function (item) {
        return '<tr>' +
          '<td>' + item.id + '</td>' +
          '<td>' + item.mappingId + '</td>' +
          '<td>' + escapeHtml(item.clientIp) + ':' + item.clientPort + '</td>' +
          '<td>' + item.listenPort + '</td>' +
          '<td>' + escapeHtml(item.forwardHost) + ':' + item.forwardPort + '</td>' +
          '<td>' + statusBadge(item.status) + '</td>' +
          '<td>' + escapeHtml(item.openedAt) + '</td>' +
          '<td>' + item.bytesUp + ' 上行<br>' + item.bytesDown + ' 下行</td>' +
          '<td><button class="compact-button" type="button" data-connection-detail="' + item.id + '">详情</button></td>' +
          '</tr>';
      }).join('') : emptyTableRow(9, '当前筛选条件下没有连接记录。请调整映射、客户端或时间范围后重试。');
      bindConnectionDetails();
      document.getElementById('connection-page-info').textContent = '第 ' + page.page + ' 页 / 共 ' + Math.max(1, Math.ceil(page.total / page.pageSize)) + ' 页';
      document.getElementById('connection-prev').disabled = page.page <= 1;
      document.getElementById('connection-next').disabled = page.page * page.pageSize >= page.total;
    }).catch(function (error) {
      setError('connection-message', error);
    }).then(function () {
      state.connectionLoading = false;
      setFilterLoadingState('connection', false);
    });
  }

  function bindConnectionDetails() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-connection-detail]'), function (button) {
      button.addEventListener('click', function () {
        api('/api/connections/' + button.getAttribute('data-connection-detail')).then(showConnectionDetail).catch(function (error) {
          setError('connection-message', error);
        });
      });
    });
  }

  function showConnectionDetail(data) {
    if (packetFeedbackTimer) {
      window.clearTimeout(packetFeedbackTimer);
      packetFeedbackTimer = null;
    }
    modal.classList.remove('modal-confirm');
    modal.classList.remove('modal-packet-detail');
    modal.classList.remove('modal-mapping-editor');
    modal.classList.remove('modal-packet-purge');
    state.packetDetail = null;
    modalTitle.textContent = '连接 #' + data.id;
    modal.setAttribute('aria-describedby', 'connection-dialog-description');
    modalBody.innerHTML = '<p id="connection-dialog-description" class="dialog-description">查看连接生命周期、关闭原因和最近报文，并继续追踪该连接关联的报文内容。</p>' +
      detailGrid(data, ['mappingId', 'clientIp', 'clientPort', 'listenIp', 'listenPort', 'forwardHost', 'forwardPort', 'remoteIp', 'remotePort', 'status', 'closeReason', 'openedAt', 'closedAt', 'bytesUp', 'bytesDown', 'errorMessage']) +
      '<div class="panel-body"><div class="dialog-section-head"><h3>最近报文</h3><button class="secondary-button" type="button" id="connection-open-packets">查看该连接的全部报文</button></div><div class="table-wrap"><table><thead><tr><th>ID</th><th>方向</th><th>大小</th><th>捕获大小</th><th>接收时间</th><th></th></tr></thead><tbody>' +
      (data.recentPackets || []).map(function (packet) {
        return '<tr><td>' + packet.id + '</td><td>' + escapeHtml(translateDirection(packet.direction)) + '</td><td>' + packet.payloadSize + '</td><td>' + packet.capturedSize + '</td><td>' + escapeHtml(packet.receivedAt) + '</td><td><button class="compact-button" type="button" data-connection-packet-detail="' + packet.id + '">查看报文</button></td></tr>';
      }).join('') + ((data.recentPackets || []).length ? '' : emptyTableRow(6, '该连接下暂时没有报文记录。')) +
      '</tbody></table></div><p id="connection-detail-message" class="form-message"></p></div>';
    openModal('connection-detail');
    setModalDismissState(false);
    document.getElementById('connection-open-packets').addEventListener('click', function () {
      goToPacketList({
        connectionId: data.id,
        mappingId: data.mappingId
      });
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-connection-packet-detail]'), function (button) {
      button.addEventListener('click', function () {
        openPacketDetailById(button.getAttribute('data-connection-packet-detail'), 'connection-detail-message');
      });
    });
    focusModalElement('#connection-open-packets');
  }

  function renderPackets() {
    clearTimer();
    view.innerHTML = pageHead(PAGE_COPY.packets.title, PAGE_COPY.packets.subtitle, '<button id="packet-purge-open" class="danger-button" type="button">清理记录</button>') +
      '<section class="panel"><form id="packet-filter-form" class="panel-body panel-stack filter-form">' +
      '<div class="filter-form-grid">' +
      field('packet-mapping-id', '映射 ID', '例如 7') +
      field('packet-connection-id', '连接 ID', '例如 123') +
      selectField('packet-direction', '方向', ['', 'REQUEST', 'RESPONSE']) +
      timeField('packet-from', '开始时间') +
      timeField('packet-to', '结束时间') +
      '</div>' +
      '<div class="filter-form-actions"><button id="packet-search" type="submit">查询</button><button id="packet-reset" class="secondary-button" type="reset">重置</button></div>' +
      '<div class="table-wrap"><table><thead><tr><th>ID</th><th>连接</th><th>映射</th><th>方向</th><th>客户端</th><th>目标</th><th>大小</th><th>接收时间</th><th></th></tr></thead><tbody id="packet-rows"></tbody></table></div>' +
      '<div class="pager"><button id="packet-prev" class="secondary-button" type="button">上一页</button><span id="packet-page-info"></span><button id="packet-next" class="secondary-button" type="button">下一页</button></div>' +
      '<p id="packet-message" class="form-message"></p></form></section>';
    document.getElementById('packet-purge-open').addEventListener('click', openPacketPurgeModal);
    if (state.pendingPacketFilters) {
      applyFilters(state.pendingPacketFilters);
      state.pendingPacketFilters = null;
    }
    document.getElementById('packet-filter-form').addEventListener('submit', function (event) {
      event.preventDefault();
      state.packetPage = 1;
      loadPackets();
    });
    document.getElementById('packet-filter-form').addEventListener('reset', function () {
      window.setTimeout(function () {
        state.packetPage = 1;
        setMessage('packet-message', '', 'error');
        loadPackets();
      }, 0);
    });
    document.getElementById('packet-prev').addEventListener('click', function () {
      state.packetPage = Math.max(1, state.packetPage - 1);
      loadPackets();
    });
    document.getElementById('packet-next').addEventListener('click', function () {
      state.packetPage += 1;
      loadPackets();
    });
    loadPackets();
  }

  function openPacketPurgeModal() {
    state.packetPurge = defaultPacketPurgeState();
    state.packetPurge.open = true;
    modal.classList.add('modal-packet-purge');
    openModal('packet-purge');
    renderPacketPurgeModal();
  }

  function renderPacketPurgeModal() {
    modalTitle.textContent = '清理报文记录';
    if (state.packetPurge.step === 'confirm') {
      modal.setAttribute('aria-describedby', 'packet-purge-description');
      modalBody.innerHTML =
        '<div class="modal-body-shell modal-danger-shell">' +
        '<p id="packet-purge-description" class="dialog-description">即将执行高风险清理。该操作会删除 SQLite 中的报文记录，并同步删除对应 payload 文件，无法恢复。</p>' +
        '<div class="dialog-danger-summary">' +
        '<strong>' + escapeHtml(packetPurgeScopeLabel(state.packetPurge.scope)) + '</strong>' +
        '<span>' + escapeHtml(packetPurgeScopeDescription(state.packetPurge.scope)) + '</span>' +
        '</div>' +
        '<p id="packet-purge-message" class="form-message">' + escapeHtml(state.packetPurge.error || '') + '</p>' +
        '<div class="modal-actions">' +
        '<button id="packet-purge-back" class="secondary-button" type="button"' + (state.packetPurge.submitting ? ' disabled' : '') + '>返回选择</button>' +
        '<button id="packet-purge-confirm" class="danger-button" type="button"' + (state.packetPurge.submitting ? ' disabled' : '') + '>' +
        escapeHtml(packetPurgeConfirmLabel(state.packetPurge.scope, state.packetPurge.submitting)) +
        '</button>' +
        '</div></div>';
      setModalDismissState(state.packetPurge.submitting);
      document.getElementById('packet-purge-back').addEventListener('click', function () {
        state.packetPurge.step = 'select';
        state.packetPurge.error = null;
        renderPacketPurgeModal();
      });
      document.getElementById('packet-purge-confirm').addEventListener('click', function () {
        purgePackets(state.packetPurge.scope);
      });
      focusModalElement('#packet-purge-back');
      return;
    }
    modal.removeAttribute('aria-describedby');
    modalBody.innerHTML =
      '<div class="modal-body-shell modal-danger-shell">' +
      '<p class="dialog-description">先选择清理范围，再确认执行。影响越大的操作会使用更谨慎的确认步骤。</p>' +
      '<div class="danger-note-list">' +
      '<button class="danger-note danger-note-action" type="button" data-packet-purge-scope="NON_TODAY"><strong>清空非当日</strong><span>保留今天的数据，删除更早日期的报文和 payload 文件。</span></button>' +
      '<button class="danger-note danger-note-action" type="button" data-packet-purge-scope="ALL"><strong>清空全部</strong><span>删除全部报文记录和 payload 文件，适合明确要从零开始时使用。</span></button>' +
      '</div>' +
      '<p id="packet-purge-message" class="form-message">' + escapeHtml(state.packetPurge.error || '') + '</p>' +
      '<div class="modal-actions">' +
      '<button id="packet-purge-cancel" class="secondary-button" type="button"' + (state.packetPurge.submitting ? ' disabled' : '') + '>取消</button>' +
      '</div></div>';
    setModalDismissState(state.packetPurge.submitting);
    document.getElementById('packet-purge-cancel').addEventListener('click', function () {
      closeModal();
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-packet-purge-scope]'), function (button) {
      button.addEventListener('click', function () {
        state.packetPurge.scope = button.getAttribute('data-packet-purge-scope');
        state.packetPurge.step = 'confirm';
        state.packetPurge.error = null;
        renderPacketPurgeModal();
      });
    });
    focusModalElement('[data-packet-purge-scope]');
  }

  function purgePackets(scope) {
    state.packetPurge.submitting = true;
    state.packetPurge.error = null;
    renderPacketPurgeModal();
    api('/api/packets/purge', { method: 'POST', body: JSON.stringify({ scope: scope }) }).then(function (result) {
      state.packetPurge = defaultPacketPurgeState();
      closeModal(true);
      state.packetPage = 1;
      loadPackets();
      setMessage('packet-message', formatPacketPurgeMessage(result), 'success');
    }).catch(function (error) {
      state.packetPurge.submitting = false;
      state.packetPurge.error = error.message;
      renderPacketPurgeModal();
    });
  }

  function formatPacketPurgeMessage(result) {
    if (!result) {
      return '清理完成';
    }
    var parts = [
      '已清理 ' + String(result.deletedPackets || 0) + ' 条报文',
      '删除 ' + String(result.deletedPayloadFiles || 0) + ' 个 payload 文件'
    ];
    if (result.keptDate) {
      parts.push('保留日期 ' + result.keptDate);
    }
    return parts.join('，');
  }

  function loadPackets() {
    state.packetLoading = true;
    setFilterLoadingState('packet', true);
    api('/api/packets' + qs({
      mappingId: valueOf('packet-mapping-id'),
      connectionId: valueOf('packet-connection-id'),
      direction: valueOf('packet-direction'),
      from: valueOf('packet-from'),
      to: valueOf('packet-to'),
      page: state.packetPage,
      pageSize: 50
    })).then(function (page) {
      var rows = page.items || [];
      document.getElementById('packet-rows').innerHTML = rows.length ? rows.map(function (item) {
        return '<tr>' +
          '<td>' + item.id + '</td>' +
          '<td>' + item.connectionId + '</td>' +
          '<td>' + item.mappingId + '</td>' +
          '<td>' + escapeHtml(translateDirection(item.direction)) + '</td>' +
          '<td>' + escapeHtml(item.clientIp) + ':' + item.clientPort + '</td>' +
          '<td>' + escapeHtml(item.targetHost) + ':' + item.targetPort + '<div class="muted">' + escapeHtml(packetSummary(item)) + '</div></td>' +
          '<td>' + item.payloadSize + '<div class="muted">已捕获 ' + item.capturedSize + (item.truncated ? '，已截断' : '') + '</div></td>' +
          '<td>' + escapeHtml(item.receivedAt) + '</td>' +
          '<td><button class="compact-button" type="button" data-packet-detail="' + item.id + '">详情</button></td>' +
          '</tr>';
      }).join('') : emptyTableRow(9, '当前筛选条件下没有报文记录。请调整连接、映射或时间范围后重试。');
      bindPacketDetails();
      document.getElementById('packet-page-info').textContent = '第 ' + page.page + ' 页 / 共 ' + Math.max(1, Math.ceil(page.total / page.pageSize)) + ' 页';
      document.getElementById('packet-prev').disabled = page.page <= 1;
      document.getElementById('packet-next').disabled = page.page * page.pageSize >= page.total;
    }).catch(function (error) {
      setError('packet-message', error);
    }).then(function () {
      state.packetLoading = false;
      setFilterLoadingState('packet', false);
    });
  }

  function bindPacketDetails() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-packet-detail]'), function (button) {
      button.addEventListener('click', function () {
        openPacketDetailById(button.getAttribute('data-packet-detail'), 'packet-message');
      });
    });
  }

  function openPacketDetailById(id, errorTarget) {
    api('/api/packets/' + id).then(showPacketDetail).catch(function (error) {
      if (errorTarget) {
        setError(errorTarget, error);
      }
    });
  }

  function showPacketDetail(data) {
    state.packetDetail = {
      data: data,
      primaryTab: 'text',
      textTab: 'raw',
      feedback: null
    };
    modal.classList.remove('modal-confirm');
    modal.classList.remove('modal-mapping-editor');
    modal.classList.remove('modal-packet-purge');
    modal.classList.add('modal-packet-detail');
    openModal('packet-detail');
    renderPacketDetailModal();
  }

  function packetSummary(item) {
    if (item.httpMethod || item.httpUri || item.httpStatus) {
      if (item.direction === 'REQUEST') {
        return [item.httpMethod || '-', item.httpUri || '-'].join(' ');
      }
      return String(item.httpStatus == null ? '-' : item.httpStatus) + (item.httpMethod ? ' ' + item.httpMethod : '');
    }
    return item.protocolFamily || '-';
  }

  function field(id, label, placeholder) {
    return '<div><label for="' + id + '">' + escapeHtml(label) + '</label><input id="' + id + '"' +
      (placeholder ? ' placeholder="' + escapeHtml(placeholder) + '"' : '') + '></div>';
  }

  function timeField(id, label) {
    return field(id, label, FILTER_TIME_PLACEHOLDER);
  }

  function applyFilters(filters) {
    if (!filters) {
      return;
    }
    Object.keys(filters).forEach(function (key) {
      var node = document.getElementById(key);
      if (node) {
        node.value = filters[key] == null ? '' : String(filters[key]);
      }
    });
  }

  function goToPacketList(filters) {
    state.pendingPacketFilters = {
      'packet-mapping-id': filters.mappingId || '',
      'packet-connection-id': filters.connectionId || ''
    };
    closeModal(true);
    if (window.location.pathname !== '/packets') {
      window.history.pushState({}, '', '/packets');
      route();
      return;
    }
    applyFilters(state.pendingPacketFilters);
    state.pendingPacketFilters = null;
    state.packetPage = 1;
    loadPackets();
  }

  function setFilterLoadingState(prefix, loading) {
    var submit = document.getElementById(prefix + '-search');
    var reset = document.getElementById(prefix + '-reset');
    if (submit) {
      submit.disabled = !!loading;
      submit.textContent = loading ? '查询中...' : '查询';
    }
    if (reset) {
      reset.disabled = !!loading;
    }
  }

  function packetPurgeScopeLabel(scope) {
    return scope === 'ALL' ? '清空全部' : '清空非当日';
  }

  function packetPurgeScopeDescription(scope) {
    return scope === 'ALL'
      ? '会删除所有报文记录和 payload 文件。'
      : '会保留今天的数据，删除更早日期的报文和 payload 文件。';
  }

  function packetPurgeConfirmLabel(scope, submitting) {
    if (submitting) {
      return '清理中...';
    }
    return scope === 'ALL' ? '确认清空全部' : '确认清空非当日';
  }

  function selectField(id, label, values) {
    return '<div><label for="' + id + '">' + escapeHtml(label) + '</label><select id="' + id + '">' +
      values.map(function (value) {
        return '<option value="' + escapeHtml(value) + '">' + escapeHtml(translateOptionLabel(value)) + '</option>';
      }).join('') + '</select></div>';
  }

  function valueOf(id) {
    var node = document.getElementById(id);
    return node ? node.value.trim() : '';
  }

  function renderPacketDetailModal() {
    if (!state.packetDetail) {
      return;
    }
    var detail = state.packetDetail;
    var data = detail.data;
    var http = packetHttpView(data);
    if (!packetJsonTabVisible(data)) {
      detail.textTab = 'raw';
    }
    modalTitle.textContent = '报文 #' + data.id;
    modal.setAttribute('aria-describedby', 'packet-dialog-description');
    modalBody.innerHTML =
      '<div class="packet-detail-shell">' +
      '<p id="packet-dialog-description" class="dialog-description">查看报文来源、预览内容和存储状态，并继续按连接或映射追踪上下文。</p>' +
      renderPacketSummary(data) +
      '<section class="packet-content-section">' +
      renderPacketToolbar(data, detail) +
      renderPacketNotices(data, detail) +
      '<div class="preview-box preview-box-single"><pre id="packet-preview-content"></pre></div>' +
      (http && http.startLine ? '<div class="preview-meta">HTTP 起始行：' + escapeHtml(http.startLine) + '</div>' : '') +
      '</section>' +
      '</div>';
    document.getElementById('packet-preview-content').textContent = packetCurrentViewContent(data, detail);
    bindPacketDetailActions();
    setModalDismissState(false);
    focusModalElement('[data-packet-context-nav], [data-packet-tab], [data-packet-copy]');
  }

  function renderPacketSummary(data) {
    return '<section class="packet-summary">' +
      '<div class="page-context-meta packet-context-meta">' +
      heroBadge('来自连接 #' + data.connectionId) +
      heroBadge('映射 #' + data.mappingId) +
      '<button class="secondary-button" type="button" data-packet-context-nav="connection">按此连接筛选</button>' +
      '</div>' +
      '<div class="packet-fact-grid">' +
      packetFact('报文 ID', data.id) +
      packetFact('方向', translateDirection(data.direction)) +
      packetFact('协议', packetProtocolLabel(data)) +
      packetFact('内容类型', data.contentType || '-') +
      packetFact('HTTP 摘要', packetHttpHeadline(data)) +
      packetFact('大小', String(data.payloadSize) + ' / ' + String(data.capturedSize)) +
      packetFact('存储', packetStoreLabel(data)) +
      packetFact('截断', translateBoolean(data.truncated)) +
      packetFact('接收时间', data.receivedAt || '-') +
      '</div>' +
      '<div class="detail-grid detail-grid-secondary">' +
      detailItemHtml('映射 / 连接', String(data.mappingId) + ' / ' + String(data.connectionId)) +
      detailItemHtml('客户端', formatEndpoint(data.clientIp, data.clientPort)) +
      detailItemHtml('监听', formatEndpoint(data.listenIp, data.listenPort)) +
      detailItemHtml('目标', formatEndpoint(data.targetHost, data.targetPort)) +
      detailItemHtml('远端', formatEndpoint(data.remoteIp, data.remotePort)) +
      detailItemHtml('序号', data.sequenceNo) +
      '</div>' +
      '</section>';
  }

  function renderPacketToolbar(data, detail) {
    var currentLabel = packetFeedbackLabel(detail, 'current', '复制当前视图');
    var bodyLabel = packetFeedbackLabel(detail, 'body', '复制请求体');
    var downloadLabel = packetFullPayloadAvailable(data) ? '下载完整负载' : '下载当前预览';
    return '<div class="content-toolbar">' +
      '<div class="tab-switch" role="tablist" aria-label="报文视图">' +
      tabButton('primary', 'text', '文本', detail.primaryTab === 'text') +
      tabButton('primary', 'hex', '十六进制', detail.primaryTab === 'hex') +
      '</div>' +
      (detail.primaryTab === 'text' && packetJsonTabVisible(data) ? '<div class="sub-tab-switch" role="tablist" aria-label="文本子视图">' +
        tabButton('text', 'raw', '原文', detail.textTab === 'raw') +
        tabButton('text', 'json', 'JSON 格式化', detail.textTab === 'json') +
      '</div>' : '<div class="sub-tab-switch sub-tab-switch-placeholder"></div>') +
      '<div class="toolbar-spacer"></div>' +
      '<button class="secondary-button" type="button" data-packet-copy="current">' + escapeHtml(currentLabel) + '</button>' +
      (packetBodyCopyAvailable(data) ? '<button class="secondary-button" type="button" data-packet-copy="body">' + escapeHtml(bodyLabel) + '</button>' : '') +
      '<a class="download-link" href="/api/packets/' + data.id + '/payload">' + escapeHtml(downloadLabel) + '</a>' +
      (detail.feedback ? '<span class="copy-feedback copy-feedback-' + escapeHtml(detail.feedback.status) + '">' + escapeHtml(detail.feedback.message) + '</span>' : '') +
      '</div>';
  }

  function renderPacketNotices(data, detail) {
    var notices = packetNotices(data, detail);
    if (!notices.length) {
      return '';
    }
    return '<div class="preview-notice-list">' + notices.map(function (notice) {
      return '<div class="preview-notice">' + escapeHtml(notice) + '</div>';
    }).join('') + '</div>';
  }

  function bindPacketDetailActions() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-packet-tab]'), function (button) {
      button.addEventListener('click', function () {
        if (!state.packetDetail) {
          return;
        }
        state.packetDetail.primaryTab = button.getAttribute('data-packet-tab');
        renderPacketDetailModal();
      });
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-packet-text-tab]'), function (button) {
      button.addEventListener('click', function () {
        if (!state.packetDetail) {
          return;
        }
        state.packetDetail.textTab = button.getAttribute('data-packet-text-tab');
        renderPacketDetailModal();
      });
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-packet-copy]'), function (button) {
      button.addEventListener('click', function () {
        copyPacketContent(button.getAttribute('data-packet-copy'));
      });
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-packet-context-nav]'), function (button) {
      button.addEventListener('click', function () {
        if (!state.packetDetail) {
          return;
        }
        goToPacketList({
          connectionId: state.packetDetail.data.connectionId,
          mappingId: state.packetDetail.data.mappingId
        });
      });
    });
  }

  function copyPacketContent(target) {
    if (!state.packetDetail) {
      return;
    }
    var detail = state.packetDetail;
    var data = detail.data;
    var content = target === 'body' ? packetBodyContent(data) : packetCurrentViewContent(data, detail);
    var suffix = packetPreviewTruncated(data) || data.truncated ? '，内容已截断' : '';
    copyText(content).then(function () {
      setPacketFeedback(target, 'success', target === 'body' ? '已复制请求体' + suffix : '已复制' + suffix);
    }).catch(function () {
      setPacketFeedback(target, 'error', '复制失败，请重试');
    });
  }

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text).catch(function () {
        return fallbackCopy(text);
      });
    }
    return fallbackCopy(text);
  }

  function fallbackCopy(text) {
    return new Promise(function (resolve, reject) {
      var textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.setAttribute('readonly', 'readonly');
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      textarea.style.left = '-9999px';
      document.body.appendChild(textarea);
      textarea.focus();
      textarea.select();
      try {
        if (document.execCommand('copy')) {
          resolve();
        } else {
          reject(new Error('copy failed'));
        }
      } catch (error) {
        reject(error);
      } finally {
        document.body.removeChild(textarea);
      }
    });
  }

  function setPacketFeedback(target, status, message) {
    if (!state.packetDetail) {
      return;
    }
    state.packetDetail.feedback = {
      target: target,
      status: status,
      message: message
    };
    if (packetFeedbackTimer) {
      window.clearTimeout(packetFeedbackTimer);
    }
    renderPacketDetailModal();
    packetFeedbackTimer = window.setTimeout(function () {
      if (!state.packetDetail) {
        return;
      }
      state.packetDetail.feedback = null;
      renderPacketDetailModal();
    }, 1600);
  }

  function packetFeedbackLabel(detail, target, fallback) {
    if (detail.feedback && detail.feedback.target === target && detail.feedback.status === 'success') {
      return '已复制';
    }
    return fallback;
  }

  function packetCurrentViewContent(data, detail) {
    if (detail.primaryTab === 'hex') {
      return packetHexText(data);
    }
    if (detail.textTab === 'json' && packetJsonTabVisible(data)) {
      return packetHttpView(data).bodyJsonPretty || '';
    }
    return packetTextRaw(data);
  }

  function packetBodyContent(data) {
    var http = packetHttpView(data);
    return http && http.bodyDetected ? http.bodyText || '' : '';
  }

  function packetJsonTabVisible(data) {
    var http = packetHttpView(data);
    return !!(http && http.bodyDetected && http.bodyJson && http.bodyJsonPretty && !http.bodyTruncated);
  }

  function packetBodyCopyAvailable(data) {
    var http = packetHttpView(data);
    return !!(http && http.bodyDetected);
  }

  function packetNotices(data, detail) {
    var notices = [];
    var http = packetHttpView(data);
    if (packetPreviewTruncated(data)) {
      notices.push('仅展示前 ' + packetPreviewBytes(data) + ' 字节');
    }
    if (packetFullPayloadAvailable(data)) {
      notices.push(packetFullPayloadComplete(data) ? '完整 payload 可下载' : '已保存受限完整 payload，下载内容可能仍不完整');
    } else if (packetStoreType(data) === 'FILE_DELETED') {
      notices.push('完整 payload 已被清理，当前仅可下载预览');
    } else if (packetStoreType(data) === 'FILE') {
      notices.push('完整 payload 文件不可用，当前下载将回退为预览');
    } else {
      notices.push('当前仅保留预览数据');
    }
    if (data.truncated) {
      notices.push('当前报文已截断');
    }
    if (detail.primaryTab === 'text' && detail.textTab === 'json') {
      if (!http || !http.bodyDetected) {
        notices.push('未识别到 HTTP Body');
      } else if (!http.bodyJson) {
        notices.push('当前内容不是 JSON');
      } else if (http.bodyTruncated) {
        notices.push('当前报文已截断，JSON 格式化不可用');
      } else if (!http.bodyJsonPretty) {
        notices.push('JSON 解析失败，无法格式化展示');
      }
    } else if (detail.primaryTab === 'text' && http && http.isHttp) {
      if (!http.bodyDetected) {
        notices.push('未识别到 HTTP Body');
      } else if (!packetJsonTabVisible(data)) {
        if (!http.bodyJson) {
          notices.push('当前内容不是 JSON');
        } else if (http.bodyTruncated) {
          notices.push('当前报文已截断，JSON 格式化不可用');
        } else if (!http.bodyJsonPretty) {
          notices.push('JSON 解析失败，无法格式化展示');
        }
      }
    }
    return notices;
  }

  function packetHttpView(data) {
    return data && data.payloadView ? data.payloadView.http : null;
  }

  function packetPreviewBytes(data) {
    return data && data.payloadView && data.payloadView.previewBytes != null ? data.payloadView.previewBytes : (data.previewBytes || 0);
  }

  function packetPreviewTruncated(data) {
    return !!(data && data.payloadView ? data.payloadView.previewTruncated : data.previewTruncated);
  }

  function packetTextRaw(data) {
    if (data && data.payloadView && data.payloadView.textRaw != null) {
      return data.payloadView.textRaw;
    }
    return decodeEscaped(data.textPreview || '');
  }

  function packetHexText(data) {
    if (data && data.payloadView && data.payloadView.hex != null) {
      return data.payloadView.hex;
    }
    return data.hexPreview || '';
  }

  function packetProtocolLabel(data) {
    var values = [];
    var protocol = translateProtocol(data.protocolFamily);
    var application = translateProtocol(data.applicationProtocol);
    if (protocol && protocol !== '-') {
      values.push(protocol);
    }
    if (application && application !== '-' && application !== protocol) {
      values.push(application);
    }
    return values.length ? values.join(' / ') : '-';
  }

  function packetStoreType(data) {
    if (data && data.payloadView && data.payloadView.storeType) {
      return data.payloadView.storeType;
    }
    return data && data.payloadStoreType ? data.payloadStoreType : 'PREVIEW_ONLY';
  }

  function packetFullPayloadAvailable(data) {
    if (data && data.fullPayloadAvailable != null) {
      return !!data.fullPayloadAvailable;
    }
    if (data && data.payloadView && data.payloadView.fullPayloadAvailable != null) {
      return !!data.payloadView.fullPayloadAvailable;
    }
    return false;
  }

  function packetFullPayloadComplete(data) {
    if (data && data.payloadComplete != null) {
      return !!data.payloadComplete;
    }
    if (data && data.payloadView && data.payloadView.fullPayloadComplete != null) {
      return !!data.payloadView.fullPayloadComplete;
    }
    return false;
  }

  function packetStoreLabel(data) {
    var type = packetStoreType(data);
    if (type === 'FILE') {
      return packetFullPayloadComplete(data) ? '文件完整' : '文件部分';
    }
    if (type === 'FILE_DELETED') {
      return '文件已清理';
    }
    if (type === 'NONE') {
      return '未保存';
    }
    return '仅预览';
  }

  function packetHttpHeadline(data) {
    if (data.httpMethod || data.httpUri || data.httpStatus != null) {
      if (data.direction === 'REQUEST') {
        return [data.httpMethod || '-', data.httpUri || '-'].join(' ');
      }
      return 'HTTP ' + String(data.httpStatus == null ? '-' : data.httpStatus) + (data.httpUri ? ' ' + data.httpUri : '');
    }
    return '-';
  }

  function packetFact(label, value) {
    return '<div class="packet-fact"><div class="label">' + escapeHtml(label) + '</div><div class="value">' + escapeHtml(value == null || value === '' ? '-' : value) + '</div></div>';
  }

  function detailItemHtml(label, value) {
    return '<div class="detail-item"><div class="label">' + escapeHtml(label) + '</div><div class="value">' + escapeHtml(value == null || value === '' ? '-' : value) + '</div></div>';
  }

  function formatEndpoint(host, port) {
    if ((host == null || host === '') && (port == null || port === '')) {
      return '-';
    }
    return String(host || '-') + ':' + String(port == null ? '-' : port);
  }

  function tabButton(group, value, label, active) {
    var attr = group === 'text' ? 'data-packet-text-tab' : 'data-packet-tab';
    return '<button class="tab-button' + (active ? ' active' : '') + '" type="button" ' + attr + '="' + escapeHtml(value) + '" role="tab" aria-selected="' + (active ? 'true' : 'false') + '">' + escapeHtml(label) + '</button>';
  }

  function detailGrid(data, keys, className) {
    return '<div class="detail-grid' + (className ? ' ' + className : '') + '">' + keys.map(function (key) {
      var value = data[key];
      if (key === 'status') {
        value = translateStatus(value);
      } else if (key === 'direction') {
        value = translateDirection(value);
      } else if (key === 'applicationProtocol' || key === 'protocolFamily') {
        value = translateProtocol(value);
      } else if (key === 'truncated') {
        value = translateBoolean(value);
      }
      return detailItemHtml(translateFieldLabel(key), value);
    }).join('') + '</div>';
  }

  function decodeEscaped(value) {
    var textarea = document.createElement('textarea');
    textarea.innerHTML = value;
    return textarea.value;
  }

  function focusModalElement(selector) {
    window.setTimeout(function () {
      if (modal.classList.contains('hidden')) {
        return;
      }
      var target = selector ? modal.querySelector(selector) : null;
      if (!target) {
        target = modal.querySelector('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href]');
      }
      if (target && target.focus) {
        target.focus();
      }
    }, 0);
  }

  function modalFocusableElements() {
    return Array.prototype.filter.call(
      modal.querySelectorAll('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'),
      function (node) {
        return !node.hidden && node.offsetParent !== null;
      }
    );
  }

  function setModalDismissState(locked) {
    if (modalClose) {
      modalClose.disabled = !!locked;
      modalClose.setAttribute('aria-disabled', locked ? 'true' : 'false');
    }
  }

  function openConfirmDialog(options) {
    state.confirmDialog = {
      open: true,
      title: options.title || '确认操作',
      description: options.description || '',
      confirmLabel: options.confirmLabel || '确认',
      cancelLabel: options.cancelLabel || '取消',
      tone: options.tone || 'default',
      action: options.action || null,
      submitting: false,
      error: null,
      returnTo: options.returnTo || null
    };
    modal.classList.add('modal-confirm');
    modal.classList.remove('modal-mapping-editor');
    modal.classList.remove('modal-packet-purge');
    modal.classList.remove('modal-packet-detail');
    if (modal.classList.contains('hidden')) {
      openModal('confirm');
    } else {
      state.modalType = 'confirm';
    }
    renderConfirmDialog();
  }

  function renderConfirmDialog() {
    var dialog = state.confirmDialog;
    modalTitle.textContent = dialog.title;
    modal.setAttribute('aria-describedby', 'confirm-dialog-description');
    modalBody.innerHTML =
      '<div class="modal-body-shell">' +
      '<p id="confirm-dialog-description" class="dialog-description">' + escapeHtml(dialog.description) + '</p>' +
      '<p id="confirm-dialog-message" class="form-message">' + escapeHtml(dialog.error || '') + '</p>' +
      '<div class="modal-actions">' +
      '<button id="confirm-dialog-cancel" class="secondary-button" type="button"' + (dialog.submitting ? ' disabled' : '') + '>' + escapeHtml(dialog.cancelLabel) + '</button>' +
      '<button id="confirm-dialog-submit" class="' + (dialog.tone === 'danger' ? 'danger-button' : '') + '" type="button"' + (dialog.submitting ? ' disabled' : '') + '>' +
      escapeHtml(dialog.submitting ? '处理中...' : dialog.confirmLabel) +
      '</button>' +
      '</div></div>';
    setModalDismissState(dialog.submitting);
    document.getElementById('confirm-dialog-cancel').addEventListener('click', function () {
      cancelConfirmDialog();
    });
    document.getElementById('confirm-dialog-submit').addEventListener('click', function () {
      submitConfirmDialog();
    });
    focusModalElement('#confirm-dialog-cancel');
  }

  function cancelConfirmDialog() {
    if (state.confirmDialog.returnTo === 'mapping-editor') {
      state.confirmDialog = defaultConfirmDialogState();
      modal.classList.remove('modal-confirm');
      modal.classList.add('modal-mapping-editor');
      state.modalType = 'mapping-editor';
      renderMappingModal();
      restoreMappingFormValues(state.mappingEditor.draftValue);
      state.mappingEditor.dirty = true;
      modal.removeAttribute('aria-describedby');
      focusModalElement('#mapping-name');
      return;
    }
    closeModal(true);
  }

  function submitConfirmDialog() {
    if (!state.confirmDialog.action) {
      closeModal(true);
      return;
    }
    state.confirmDialog.submitting = true;
    state.confirmDialog.error = null;
    renderConfirmDialog();
    if (state.confirmDialog.action.type === 'delete-mapping') {
      mappingAction(state.confirmDialog.action.mappingId, 'delete').then(function () {
        closeModal(true);
      }).catch(function (error) {
        state.confirmDialog.submitting = false;
        state.confirmDialog.error = error.message;
        renderConfirmDialog();
      });
      return;
    }
    if (state.confirmDialog.action.type === 'discard-mapping-close') {
      closeModal(true);
    }
  }

  function openModal(type) {
    if (modal.classList.contains('hidden')) {
      state.modalReturnFocus = document.activeElement && document.activeElement !== document.body ? document.activeElement : null;
    }
    state.modalType = type || null;
    document.body.classList.add('modal-open');
    modal.classList.remove('hidden');
    setModalDismissState(false);
  }

  function closeModal(force) {
    if (!force && !canCloseModal()) {
      return;
    }
    if (packetFeedbackTimer) {
      window.clearTimeout(packetFeedbackTimer);
      packetFeedbackTimer = null;
    }
    state.packetDetail = null;
    state.mappingEditor = defaultMappingEditorState();
    state.packetPurge = defaultPacketPurgeState();
    state.confirmDialog = defaultConfirmDialogState();
    state.modalType = null;
    modal.classList.remove('modal-packet-detail');
    modal.classList.remove('modal-mapping-editor');
    modal.classList.remove('modal-packet-purge');
    modal.classList.remove('modal-confirm');
    document.body.classList.remove('modal-open');
    modal.classList.add('hidden');
    modalTitle.textContent = '';
    modalBody.innerHTML = '';
    modal.removeAttribute('aria-describedby');
    if (state.modalReturnFocus && document.contains(state.modalReturnFocus)) {
      state.modalReturnFocus.focus();
    }
    state.modalReturnFocus = null;
  }

  function canCloseModal() {
    if (state.modalType === 'mapping-editor' && state.mappingEditor.open && state.mappingEditor.dirty && !state.mappingEditor.submitting) {
      state.mappingEditor.draftValue = readMappingForm();
      openConfirmDialog({
        title: '放弃未保存的修改',
        description: '当前表单内容尚未保存。关闭后这些修改会丢失。',
        confirmLabel: '放弃修改',
        tone: 'danger',
        action: { type: 'discard-mapping-close' },
        returnTo: 'mapping-editor'
      });
      return false;
    }
    if (state.modalType === 'confirm' && state.confirmDialog.returnTo === 'mapping-editor' && !state.confirmDialog.submitting) {
      cancelConfirmDialog();
      return false;
    }
    return !(state.mappingEditor.submitting || state.packetPurge.submitting || state.confirmDialog.submitting);
  }

  function clearTimer() {
    if (refreshTimer) {
      window.clearInterval(refreshTimer);
      refreshTimer = null;
    }
  }

  function route() {
    if (!modal.classList.contains('hidden')) {
      closeModal(true);
    }
    toggleNav(false);
    setActiveNav();
    if (window.location.pathname === '/mappings') {
      renderMappings();
    } else if (window.location.pathname === '/connections') {
      renderConnections();
    } else if (window.location.pathname === '/packets') {
      renderPackets();
    } else {
      renderRuntime();
    }
  }

  document.getElementById('logout-button').addEventListener('click', function () {
    api('/api/logout', { method: 'POST' }).then(function () {
      window.location.href = '/login';
    });
  });
  if (navToggle) {
    navToggle.addEventListener('click', function () {
      toggleNav();
    });
  }
  if (navBackdrop) {
    navBackdrop.addEventListener('click', function () {
      toggleNav(false);
    });
  }
  Array.prototype.forEach.call(document.querySelectorAll('[data-nav]'), function (link) {
    link.addEventListener('click', function () {
      toggleNav(false);
    });
  });
  modalClose.addEventListener('click', closeModal);
  modal.addEventListener('click', function (event) {
    if (event.target === modal) {
      closeModal();
    }
  });
  document.addEventListener('keydown', function (event) {
    if (!modal.classList.contains('hidden') && event.key === 'Tab') {
      var focusable = modalFocusableElements();
      var first = focusable[0];
      var last = focusable[focusable.length - 1];
      if (!focusable.length) {
        event.preventDefault();
      } else if (!modal.contains(document.activeElement)) {
        event.preventDefault();
        first.focus();
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
    if (event.key === 'Escape') {
      if (document.body.classList.contains('nav-open')) {
        toggleNav(false);
      }
      if (!modal.classList.contains('hidden')) {
        closeModal();
      }
    }
  });
  window.addEventListener('popstate', route);

  route();
})();
