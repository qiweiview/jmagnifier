(function () {
  var form = document.getElementById('login-form');
  var message = document.getElementById('login-message');
  var password = document.getElementById('password');

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    message.textContent = '';
    fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: password.value })
    }).then(function (response) {
      return response.json();
    }).then(function (body) {
      if (body.success) {
        window.location.href = '/';
      } else {
        message.textContent = body.error && body.error.message ? body.error.message : '登录失败';
      }
    }).catch(function () {
      message.textContent = '登录失败';
    });
  });
})();
