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

  function api(path, options) {
    var request = options || {};
    request.headers = request.headers || {};
    if (request.body && !request.headers['Content-Type']) {
      request.headers['Content-Type'] = 'application/json';
    }
    return fetch(path, request).then(function (response) {
      if (response.status === 401) {
        window.location.href = '/login';
        return Promise.reject(new Error('login required'));
      }
      return response.json().then(function (body) {
        if (!body.success) {
          var message = body.error && body.error.message ? body.error.message : 'request failed';
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
    return '<span class="status ' + escapeHtml(value) + '">' + escapeHtml(status || '-') + '</span>';
  }

  function metric(label, value) {
    return '<div class="metric"><div class="label">' + escapeHtml(label) + '</div><div class="value">' + escapeHtml(value) + '</div></div>';
  }

  function pageHead(title, subtitle, action) {
    return '<div class="page-head"><div><h1>' + escapeHtml(title) + '</h1><p>' + escapeHtml(subtitle || '') + '</p></div>' + (action || '') + '</div>';
  }

  function renderRuntime() {
    clearTimer();
    view.innerHTML = pageHead('Runtime', 'Live process and capture counters') + '<div id="runtime-metrics" class="grid metrics"></div>';
    function load() {
      api('/api/runtime').then(function (data) {
        document.getElementById('runtime-metrics').innerHTML = [
          metric('Mappings', data.mappings),
          metric('Running', data.runningMappings),
          metric('Stopped', data.stoppedMappings),
          metric('Failed', data.failedMappings),
          metric('Active connections', data.activeConnections),
          metric('Capture queue', data.captureQueueSize + ' / ' + data.captureQueueCapacity),
          metric('Spill files', data.spillFileCount),
          metric('Spill bytes', data.spillBytes),
          metric('Packets written', data.packetsWritten),
          metric('Packets spilled', data.packetsSpilled),
          metric('Packets dropped', data.packetsDropped),
          metric('Writer error', data.lastWriterError || '-')
        ].join('');
      }).catch(function (error) {
        view.innerHTML = pageHead('Runtime', '') + '<p class="error-text">' + escapeHtml(error.message) + '</p>';
      });
    }
    load();
    refreshTimer = window.setInterval(load, 3000);
  }

  function renderMappings() {
    clearTimer();
    view.innerHTML = pageHead('Mappings', 'Runtime forwarding rules') +
      '<div class="split">' +
      '<section class="panel"><div class="panel-head"><h2 id="mapping-form-title">New mapping</h2></div><div class="panel-body">' +
      '<form id="mapping-form" class="form-grid">' +
      '<input type="hidden" id="mapping-id">' +
      '<div><label>Name</label><input id="mapping-name" required></div>' +
      '<div><label>Listen port</label><input id="mapping-listen-port" type="number" min="0" max="65535" required></div>' +
      '<div><label>Forward host</label><input id="mapping-forward-host" required></div>' +
      '<div><label>Forward port</label><input id="mapping-forward-port" type="number" min="0" max="65535" required></div>' +
      '<label class="check-row"><input id="mapping-enabled" type="checkbox" checked> Enabled</label>' +
      '<button type="submit">Save</button><button id="mapping-reset" class="secondary-button" type="button">Reset</button>' +
      '<p id="mapping-message" class="form-message"></p>' +
      '</form></div></section>' +
      '<section class="panel"><div class="panel-head"><h2>Mappings</h2><button id="mapping-refresh" class="compact-button" type="button">Refresh</button></div><div class="table-wrap"><table><thead><tr><th>ID</th><th>Name</th><th>Listen</th><th>Target</th><th>Status</th><th>Connections</th><th>Last error</th><th></th></tr></thead><tbody id="mapping-rows"></tbody></table></div></section>' +
      '</div>';
    document.getElementById('mapping-form').addEventListener('submit', saveMapping);
    document.getElementById('mapping-reset').addEventListener('click', resetMappingForm);
    document.getElementById('mapping-refresh').addEventListener('click', loadMappings);
    loadMappings();
  }

  function loadMappings() {
    api('/api/mappings').then(function (items) {
      state.mappings = items || [];
      document.getElementById('mapping-rows').innerHTML = state.mappings.map(function (item) {
        return '<tr>' +
          '<td>' + item.id + '</td>' +
          '<td>' + escapeHtml(item.name) + '<div class="muted">' + (item.enabled ? 'enabled' : 'disabled') + '</div></td>' +
          '<td>' + item.listenPort + '</td>' +
          '<td>' + escapeHtml(item.forwardHost) + ':' + item.forwardPort + '</td>' +
          '<td>' + statusBadge(item.status) + '</td>' +
          '<td>' + item.activeConnections + '</td>' +
          '<td class="error-text">' + escapeHtml(item.lastError || '') + '</td>' +
          '<td class="actions">' +
          '<button class="compact-button" type="button" data-edit="' + item.id + '">Edit</button>' +
          '<button class="compact-button" type="button" data-start="' + item.id + '">Start</button>' +
          '<button class="compact-button" type="button" data-stop="' + item.id + '">Stop</button>' +
          '<button class="danger-button" type="button" data-delete="' + item.id + '">Delete</button>' +
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
        if (window.confirm('Delete mapping ' + button.getAttribute('data-delete') + '?')) {
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
    document.getElementById('mapping-form-title').textContent = 'Edit mapping #' + id;
    document.getElementById('mapping-id').value = item.id;
    document.getElementById('mapping-name').value = item.name || '';
    document.getElementById('mapping-listen-port').value = item.listenPort;
    document.getElementById('mapping-forward-host').value = item.forwardHost || '';
    document.getElementById('mapping-forward-port').value = item.forwardPort;
    document.getElementById('mapping-enabled').checked = !!item.enabled;
  }

  function resetMappingForm() {
    document.getElementById('mapping-form-title').textContent = 'New mapping';
    document.getElementById('mapping-form').reset();
    document.getElementById('mapping-id').value = '';
    document.getElementById('mapping-enabled').checked = true;
    setError('mapping-message', null);
  }

  function readMappingForm() {
    return {
      name: document.getElementById('mapping-name').value.trim(),
      enabled: document.getElementById('mapping-enabled').checked,
      listenPort: Number(document.getElementById('mapping-listen-port').value),
      forwardHost: document.getElementById('mapping-forward-host').value.trim(),
      forwardPort: Number(document.getElementById('mapping-forward-port').value)
    };
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
    view.innerHTML = pageHead('Connections', 'Stored TCP connection records') +
      '<section class="panel"><div class="panel-body">' +
      '<div class="toolbar">' +
      field('connection-mapping-id', 'Mapping ID') +
      field('connection-client-ip', 'Client IP') +
      selectField('connection-status', 'Status', ['', 'OPENING', 'OPEN', 'CLOSED', 'FAILED']) +
      field('connection-from', 'From') +
      field('connection-to', 'To') +
      '<button id="connection-search" type="button">Search</button>' +
      '</div><div class="table-wrap"><table><thead><tr><th>ID</th><th>Mapping</th><th>Client</th><th>Listen</th><th>Target</th><th>Status</th><th>Opened</th><th>Bytes</th><th></th></tr></thead><tbody id="connection-rows"></tbody></table></div>' +
      '<div class="pager"><button id="connection-prev" class="secondary-button" type="button">Prev</button><span id="connection-page-info"></span><button id="connection-next" class="secondary-button" type="button">Next</button></div>' +
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
          '<td>' + item.bytesUp + ' up<br>' + item.bytesDown + ' down</td>' +
          '<td><button class="compact-button" type="button" data-connection-detail="' + item.id + '">Detail</button></td>' +
          '</tr>';
      }).join('');
      bindConnectionDetails();
      document.getElementById('connection-page-info').textContent = 'Page ' + page.page + ' of ' + Math.max(1, Math.ceil(page.total / page.pageSize));
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
    modalTitle.textContent = 'Connection #' + data.id;
    modalBody.innerHTML = detailGrid(data, ['mappingId', 'clientIp', 'clientPort', 'listenIp', 'listenPort', 'forwardHost', 'forwardPort', 'remoteIp', 'remotePort', 'status', 'closeReason', 'openedAt', 'closedAt', 'bytesUp', 'bytesDown', 'errorMessage']) +
      '<div class="panel-body"><h3>Recent packets</h3><div class="table-wrap"><table><thead><tr><th>ID</th><th>Direction</th><th>Size</th><th>Captured</th><th>Received</th></tr></thead><tbody>' +
      (data.recentPackets || []).map(function (packet) {
        return '<tr><td>' + packet.id + '</td><td>' + escapeHtml(packet.direction) + '</td><td>' + packet.payloadSize + '</td><td>' + packet.capturedSize + '</td><td>' + escapeHtml(packet.receivedAt) + '</td></tr>';
      }).join('') + '</tbody></table></div></div>';
    openModal();
  }

  function renderPackets() {
    clearTimer();
    view.innerHTML = pageHead('Packets', 'Captured payload summaries') +
      '<section class="panel"><div class="panel-body">' +
      '<div class="toolbar">' +
      field('packet-mapping-id', 'Mapping ID') +
      field('packet-connection-id', 'Connection ID') +
      selectField('packet-direction', 'Direction', ['', 'REQUEST', 'RESPONSE']) +
      field('packet-from', 'From') +
      field('packet-to', 'To') +
      '<button id="packet-search" type="button">Search</button>' +
      '</div><div class="table-wrap"><table><thead><tr><th>ID</th><th>Connection</th><th>Mapping</th><th>Direction</th><th>Client</th><th>Target</th><th>Size</th><th>Received</th><th></th></tr></thead><tbody id="packet-rows"></tbody></table></div>' +
      '<div class="pager"><button id="packet-prev" class="secondary-button" type="button">Prev</button><span id="packet-page-info"></span><button id="packet-next" class="secondary-button" type="button">Next</button></div>' +
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
          '<td>' + escapeHtml(item.direction) + '</td>' +
          '<td>' + escapeHtml(item.clientIp) + ':' + item.clientPort + '</td>' +
          '<td>' + escapeHtml(item.targetHost) + ':' + item.targetPort + '</td>' +
          '<td>' + item.payloadSize + '<div class="muted">captured ' + item.capturedSize + (item.truncated ? ', truncated' : '') + '</div></td>' +
          '<td>' + escapeHtml(item.receivedAt) + '</td>' +
          '<td><button class="compact-button" type="button" data-packet-detail="' + item.id + '">Detail</button></td>' +
          '</tr>';
      }).join('');
      bindPacketDetails();
      document.getElementById('packet-page-info').textContent = 'Page ' + page.page + ' of ' + Math.max(1, Math.ceil(page.total / page.pageSize));
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
    modalTitle.textContent = 'Packet #' + data.id;
    modalBody.innerHTML = detailGrid(data, ['mappingId', 'connectionId', 'direction', 'sequenceNo', 'clientIp', 'clientPort', 'listenIp', 'listenPort', 'targetHost', 'targetPort', 'remoteIp', 'remotePort', 'payloadSize', 'capturedSize', 'truncated', 'receivedAt']) +
      '<div class="panel-body"><a class="download-link" href="/api/packets/' + data.id + '/payload">Download payload</a></div>' +
      '<div class="preview-grid"><div><h3>Text</h3><div class="preview-box"><pre id="text-preview"></pre></div></div><div><h3>Hex</h3><div class="preview-box"><pre>' + escapeHtml(data.hexPreview || '') + '</pre></div></div></div>';
    openModal();
    document.getElementById('text-preview').textContent = decodeEscaped(data.textPreview || '');
  }

  function field(id, label) {
    return '<div><label for="' + id + '">' + escapeHtml(label) + '</label><input id="' + id + '"></div>';
  }

  function selectField(id, label, values) {
    return '<div><label for="' + id + '">' + escapeHtml(label) + '</label><select id="' + id + '">' +
      values.map(function (value) {
        return '<option value="' + escapeHtml(value) + '">' + escapeHtml(value || 'Any') + '</option>';
      }).join('') + '</select></div>';
  }

  function valueOf(id) {
    var node = document.getElementById(id);
    return node ? node.value.trim() : '';
  }

  function detailGrid(data, keys) {
    return '<div class="detail-grid">' + keys.map(function (key) {
      return '<div class="detail-item"><div class="label">' + escapeHtml(key) + '</div><div class="value">' + escapeHtml(data[key] == null ? '-' : data[key]) + '</div></div>';
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
