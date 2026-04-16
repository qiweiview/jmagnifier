(function () {
  var form = document.getElementById('login-form');
  var message = document.getElementById('login-message');
  var password = document.getElementById('password');
  var submit = document.getElementById('login-submit');

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    message.textContent = '';
    submit.disabled = true;
    submit.textContent = '登录中...';
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
    }).then(function () {
      submit.disabled = false;
      submit.textContent = '进入控制台';
    });
  });
})();
