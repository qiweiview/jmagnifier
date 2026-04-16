(function () {
  var view = document.getElementById('view');
  var modal = document.getElementById('modal');
  var modalTitle = document.getElementById('modal-title');
  var modalBody = document.getElementById('modal-body');
  var modalClose = document.getElementById('modal-close');
  var refreshTimer = null;
  var state = {
    mappings: [],
    connectionPage: 1,
    packetPage: 1
  };
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
    var labels = {
      tcp: 'TCP',
      http: 'HTTP',
      https: 'HTTPS',
      tls: 'TLS'
    };
    return labels[protocol] || protocol || '-';
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
  }

  function setError(target, error) {
    var node = document.getElementById(target);
    if (node) {
      node.textContent = error && error.message ? error.message : '';
    }
  }

  function statusBadge(status) {
    var value = String(status || '').toLowerCase();
    return '<span class="status ' + escapeHtml(value) + '">' + escapeHtml(translateStatus(status)) + '</span>';
  }

  function metric(label, value) {
    return '<div class="metric"><div class="label">' + escapeHtml(label) + '</div><div class="value">' + escapeHtml(value) + '</div></div>';
  }

  function pageHead(title, subtitle, action) {
    return '<div class="page-head"><div><h1>' + escapeHtml(title) + '</h1><p>' + escapeHtml(subtitle || '') + '</p></div>' + (action || '') + '</div>';
  }

  function renderRuntime() {
    clearTimer();
    view.innerHTML = pageHead('运行状态', '进程实时状态与抓包统计') + '<div id="runtime-metrics" class="grid metrics"></div>';
    function load() {
      api('/api/runtime').then(function (data) {
        document.getElementById('runtime-metrics').innerHTML = [
          metric('映射总数', data.mappings),
          metric('运行中', data.runningMappings),
          metric('已停止', data.stoppedMappings),
          metric('失败', data.failedMappings),
          metric('活跃连接', data.activeConnections),
          metric('抓包队列', data.captureQueueSize + ' / ' + data.captureQueueCapacity),
          metric('落盘文件', data.spillFileCount),
          metric('落盘字节', data.spillBytes),
          metric('已写入报文', data.packetsWritten),
          metric('已落盘报文', data.packetsSpilled),
          metric('已丢弃报文', data.packetsDropped),
          metric('写入错误', data.lastWriterError || '-')
        ].join('');
      }).catch(function (error) {
        view.innerHTML = pageHead('运行状态', '') + '<p class="error-text">' + escapeHtml(error.message) + '</p>';
      });
    }
    load();
    refreshTimer = window.setInterval(load, 3000);
  }

  function renderMappings() {
    clearTimer();
    view.innerHTML = pageHead('映射配置', '运行时转发规则管理') +
      '<div class="split">' +
      '<section class="panel"><div class="panel-head"><h2 id="mapping-form-title">新建映射</h2></div><div class="panel-body">' +
      '<form id="mapping-form" class="form-grid">' +
      '<input type="hidden" id="mapping-id">' +
      '<div><label>名称</label><input id="mapping-name" required></div>' +
      '<div><label>监听端口</label><input id="mapping-listen-port" type="number" min="0" max="65535" required></div>' +
      '<div><label>监听协议</label><select id="mapping-listen-protocol"><option value="tcp">tcp</option><option value="http">http</option></select></div>' +
      '<label class="check-row"><input id="mapping-listen-tls-enabled" type="checkbox"> 启用监听 TLS</label>' +
      '<div id="mapping-listen-tls-fields">' +
      '<div><label>监听证书文件</label><input id="mapping-listen-cert-file"></div>' +
      '<div><label>监听私钥文件</label><input id="mapping-listen-key-file"></div>' +
      '<div><label>监听私钥密码</label><input id="mapping-listen-key-password" type="password"></div>' +
      '</div>' +
      '<div><label>转发主机</label><input id="mapping-forward-host" required></div>' +
      '<div><label>转发端口</label><input id="mapping-forward-port" type="number" min="0" max="65535" required></div>' +
      '<div><label>转发协议</label><select id="mapping-forward-protocol"><option value="tcp">tcp</option><option value="http">http</option></select></div>' +
      '<label class="check-row"><input id="mapping-forward-tls-enabled" type="checkbox"> 启用转发 TLS</label>' +
      '<div id="mapping-forward-tls-fields">' +
      '<div><label>转发 SNI 主机</label><input id="mapping-forward-sni-host"></div>' +
      '<label class="check-row"><input id="mapping-forward-insecure" type="checkbox"> 跳过证书校验</label>' +
      '<div><label>转发信任证书文件</label><input id="mapping-forward-trust-cert"></div>' +
      '</div>' +
      '<div id="mapping-http-fields">' +
      '<label class="check-row"><input id="mapping-http-rewrite-host" type="checkbox" checked> 重写 Host</label>' +
      '<label class="check-row"><input id="mapping-http-add-x-forwarded" type="checkbox" checked> 添加 X-Forwarded 头</label>' +
      '<div><label>HTTP 最大对象大小</label><input id="mapping-http-max-object-size" type="number" min="1" value="1048576"></div>' +
      '</div>' +
      '<label class="check-row"><input id="mapping-enabled" type="checkbox" checked> 启用</label>' +
      '<button type="submit">保存</button><button id="mapping-reset" class="secondary-button" type="button">重置</button>' +
      '<p id="mapping-message" class="form-message"></p>' +
      '</form></div></section>' +
      '<section class="panel"><div class="panel-head"><h2>映射列表</h2><button id="mapping-refresh" class="compact-button" type="button">刷新</button></div><div class="table-wrap"><table><thead><tr><th>ID</th><th>名称</th><th>监听</th><th>转发</th><th>状态</th><th>连接数</th><th>最近错误</th><th></th></tr></thead><tbody id="mapping-rows"></tbody></table></div></section>' +
      '</div>';
    document.getElementById('mapping-form').addEventListener('submit', saveMapping);
    document.getElementById('mapping-reset').addEventListener('click', resetMappingForm);
    document.getElementById('mapping-refresh').addEventListener('click', loadMappings);
    bindMappingFormToggles();
    resetMappingForm();
    loadMappings();
  }

  function loadMappings() {
    api('/api/mappings').then(function (items) {
      state.mappings = items || [];
      document.getElementById('mapping-rows').innerHTML = state.mappings.map(function (item) {
        return '<tr>' +
          '<td>' + item.id + '</td>' +
          '<td>' + escapeHtml(item.name) + '<div class="muted">' + (item.enabled ? '已启用' : '已禁用') + '</div></td>' +
          '<td>' + item.listenPort + '<div class="muted">' + escapeHtml(translateProtocol(item.listenMode || 'tcp')) + '</div></td>' +
          '<td>' + escapeHtml(item.forwardHost) + ':' + item.forwardPort + '<div class="muted">' + escapeHtml(translateProtocol(item.forwardMode || 'tcp')) + '</div></td>' +
          '<td>' + statusBadge(item.status) + '</td>' +
          '<td>' + item.activeConnections + '</td>' +
          '<td class="error-text">' + escapeHtml(item.lastError || '') + '</td>' +
          '<td class="actions">' +
          '<button class="compact-button" type="button" data-edit="' + item.id + '">编辑</button>' +
          '<button class="compact-button" type="button" data-start="' + item.id + '">启动</button>' +
          '<button class="compact-button" type="button" data-stop="' + item.id + '">停止</button>' +
          '<button class="danger-button" type="button" data-delete="' + item.id + '">删除</button>' +
          '</td></tr>';
      }).join('');
      bindMappingActions();
    }).catch(function (error) {
      setError('mapping-message', error);
    });
  }

  function bindMappingActions() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-edit]'), function (button) {
      button.addEventListener('click', function () { editMapping(Number(button.getAttribute('data-edit'))); });
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-start]'), function (button) {
      button.addEventListener('click', function () { mappingAction(Number(button.getAttribute('data-start')), 'start'); });
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-stop]'), function (button) {
      button.addEventListener('click', function () { mappingAction(Number(button.getAttribute('data-stop')), 'stop'); });
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-delete]'), function (button) {
      button.addEventListener('click', function () {
        if (window.confirm('确认删除映射 ' + button.getAttribute('data-delete') + ' 吗？')) {
          api('/api/mappings/' + button.getAttribute('data-delete'), { method: 'DELETE' }).then(loadMappings).catch(function (error) {
            setError('mapping-message', error);
          });
        }
      });
    });
  }

  function editMapping(id) {
    var item = state.mappings.filter(function (mapping) { return mapping.id === id; })[0];
    if (!item) {
      return;
    }
    var listen = item.listen || {};
    var forward = item.forward || {};
    var listenTls = listen.tls || {};
    var forwardTls = forward.tls || {};
    var http = item.http || {};
    document.getElementById('mapping-form-title').textContent = '编辑映射 #' + id;
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
  }

  function resetMappingForm() {
    document.getElementById('mapping-form-title').textContent = '新建映射';
    document.getElementById('mapping-form').reset();
    document.getElementById('mapping-id').value = '';
    document.getElementById('mapping-listen-protocol').value = 'tcp';
    document.getElementById('mapping-forward-protocol').value = 'tcp';
    document.getElementById('mapping-enabled').checked = true;
    document.getElementById('mapping-http-rewrite-host').checked = true;
    document.getElementById('mapping-http-add-x-forwarded').checked = true;
    document.getElementById('mapping-http-max-object-size').value = 1048576;
    syncMappingFormVisibility();
    setError('mapping-message', null);
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

  function syncMappingFormVisibility() {
    var listenProtocol = valueOf('mapping-listen-protocol') || 'tcp';
    var forwardProtocol = valueOf('mapping-forward-protocol') || 'tcp';
    var isHttp = listenProtocol === 'http' || forwardProtocol === 'http';
    document.getElementById('mapping-http-fields').hidden = !isHttp;
    document.getElementById('mapping-listen-tls-fields').hidden = !document.getElementById('mapping-listen-tls-enabled').checked;
    document.getElementById('mapping-forward-tls-fields').hidden = !document.getElementById('mapping-forward-tls-enabled').checked;
  }

  function saveMapping(event) {
    event.preventDefault();
    var id = document.getElementById('mapping-id').value;
    var path = id ? '/api/mappings/' + id : '/api/mappings';
    var method = id ? 'PUT' : 'POST';
    api(path, { method: method, body: JSON.stringify(readMappingForm()) }).then(function () {
      resetMappingForm();
      loadMappings();
    }).catch(function (error) {
      setError('mapping-message', error);
    });
  }

  function mappingAction(id, action) {
    api('/api/mappings/' + id + '/' + action, { method: 'POST' }).then(loadMappings).catch(function (error) {
      setError('mapping-message', error);
    });
  }

  function renderConnections() {
    clearTimer();
    view.innerHTML = pageHead('连接记录', '已保存的 TCP 连接记录') +
      '<section class="panel"><div class="panel-body">' +
      '<div class="toolbar">' +
      field('connection-mapping-id', '映射 ID') +
      field('connection-client-ip', '客户端 IP') +
      selectField('connection-status', '状态', ['', 'OPENING', 'OPEN', 'CLOSED', 'FAILED']) +
      field('connection-from', '开始时间') +
      field('connection-to', '结束时间') +
      '<button id="connection-search" type="button">查询</button>' +
      '</div><div class="table-wrap"><table><thead><tr><th>ID</th><th>映射</th><th>客户端</th><th>监听</th><th>目标</th><th>状态</th><th>打开时间</th><th>字节数</th><th></th></tr></thead><tbody id="connection-rows"></tbody></table></div>' +
      '<div class="pager"><button id="connection-prev" class="secondary-button" type="button">上一页</button><span id="connection-page-info"></span><button id="connection-next" class="secondary-button" type="button">下一页</button></div>' +
      '<p id="connection-message" class="form-message"></p></div></section>';
    document.getElementById('connection-search').addEventListener('click', function () {
      state.connectionPage = 1;
      loadConnections();
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
      document.getElementById('connection-rows').innerHTML = rows.map(function (item) {
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
      }).join('');
      bindConnectionDetails();
      document.getElementById('connection-page-info').textContent = '第 ' + page.page + ' 页 / 共 ' + Math.max(1, Math.ceil(page.total / page.pageSize)) + ' 页';
      document.getElementById('connection-prev').disabled = page.page <= 1;
      document.getElementById('connection-next').disabled = page.page * page.pageSize >= page.total;
    }).catch(function (error) {
      setError('connection-message', error);
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
    modalTitle.textContent = '连接 #' + data.id;
    modalBody.innerHTML = detailGrid(data, ['mappingId', 'clientIp', 'clientPort', 'listenIp', 'listenPort', 'forwardHost', 'forwardPort', 'remoteIp', 'remotePort', 'status', 'closeReason', 'openedAt', 'closedAt', 'bytesUp', 'bytesDown', 'errorMessage']) +
      '<div class="panel-body"><h3>最近报文</h3><div class="table-wrap"><table><thead><tr><th>ID</th><th>方向</th><th>大小</th><th>捕获大小</th><th>接收时间</th></tr></thead><tbody>' +
      (data.recentPackets || []).map(function (packet) {
        return '<tr><td>' + packet.id + '</td><td>' + escapeHtml(translateDirection(packet.direction)) + '</td><td>' + packet.payloadSize + '</td><td>' + packet.capturedSize + '</td><td>' + escapeHtml(packet.receivedAt) + '</td></tr>';
      }).join('') + '</tbody></table></div></div>';
    openModal();
  }

  function renderPackets() {
    clearTimer();
    view.innerHTML = pageHead('报文记录', '已捕获报文摘要') +
      '<section class="panel"><div class="panel-body">' +
      '<div class="toolbar">' +
      field('packet-mapping-id', '映射 ID') +
      field('packet-connection-id', '连接 ID') +
      selectField('packet-direction', '方向', ['', 'REQUEST', 'RESPONSE']) +
      field('packet-from', '开始时间') +
      field('packet-to', '结束时间') +
      '<button id="packet-search" type="button">查询</button>' +
      '</div><div class="table-wrap"><table><thead><tr><th>ID</th><th>连接</th><th>映射</th><th>方向</th><th>客户端</th><th>目标</th><th>大小</th><th>接收时间</th><th></th></tr></thead><tbody id="packet-rows"></tbody></table></div>' +
      '<div class="pager"><button id="packet-prev" class="secondary-button" type="button">上一页</button><span id="packet-page-info"></span><button id="packet-next" class="secondary-button" type="button">下一页</button></div>' +
      '<p id="packet-message" class="form-message"></p></div></section>';
    document.getElementById('packet-search').addEventListener('click', function () {
      state.packetPage = 1;
      loadPackets();
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

  function loadPackets() {
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
      document.getElementById('packet-rows').innerHTML = rows.map(function (item) {
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
      }).join('');
      bindPacketDetails();
      document.getElementById('packet-page-info').textContent = '第 ' + page.page + ' 页 / 共 ' + Math.max(1, Math.ceil(page.total / page.pageSize)) + ' 页';
      document.getElementById('packet-prev').disabled = page.page <= 1;
      document.getElementById('packet-next').disabled = page.page * page.pageSize >= page.total;
    }).catch(function (error) {
      setError('packet-message', error);
    });
  }

  function bindPacketDetails() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-packet-detail]'), function (button) {
      button.addEventListener('click', function () {
        api('/api/packets/' + button.getAttribute('data-packet-detail')).then(showPacketDetail).catch(function (error) {
          setError('packet-message', error);
        });
      });
    });
  }

  function showPacketDetail(data) {
    modalTitle.textContent = '报文 #' + data.id;
    modalBody.innerHTML = detailGrid(data, ['mappingId', 'connectionId', 'direction', 'sequenceNo', 'protocolFamily', 'applicationProtocol', 'contentType', 'httpMethod', 'httpUri', 'httpStatus', 'clientIp', 'clientPort', 'listenIp', 'listenPort', 'targetHost', 'targetPort', 'remoteIp', 'remotePort', 'payloadSize', 'capturedSize', 'truncated', 'receivedAt']) +
      '<div class="panel-body"><a class="download-link" href="/api/packets/' + data.id + '/payload">下载负载</a></div>' +
      '<div class="preview-grid"><div><h3>文本</h3><div class="preview-box"><pre id="text-preview"></pre></div></div><div><h3>十六进制</h3><div class="preview-box"><pre>' + escapeHtml(data.hexPreview || '') + '</pre></div></div></div>';
    openModal();
    document.getElementById('text-preview').textContent = decodeEscaped(data.textPreview || '');
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

  function field(id, label) {
    return '<div><label for="' + id + '">' + escapeHtml(label) + '</label><input id="' + id + '"></div>';
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

  function detailGrid(data, keys) {
    return '<div class="detail-grid">' + keys.map(function (key) {
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
      return '<div class="detail-item"><div class="label">' + escapeHtml(translateFieldLabel(key)) + '</div><div class="value">' + escapeHtml(value == null ? '-' : value) + '</div></div>';
    }).join('') + '</div>';
  }

  function decodeEscaped(value) {
    var textarea = document.createElement('textarea');
    textarea.innerHTML = value;
    return textarea.value;
  }

  function openModal() {
    modal.classList.remove('hidden');
  }

  function closeModal() {
    modal.classList.add('hidden');
    modalTitle.textContent = '';
    modalBody.innerHTML = '';
  }

  function clearTimer() {
    if (refreshTimer) {
      window.clearInterval(refreshTimer);
      refreshTimer = null;
    }
  }

  function route() {
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
  modalClose.addEventListener('click', closeModal);
  modal.addEventListener('click', function (event) {
    if (event.target === modal) {
      closeModal();
    }
  });
  window.addEventListener('popstate', route);

  route();
})();
