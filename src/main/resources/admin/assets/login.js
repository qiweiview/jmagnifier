(function () {
  var form = document.getElementById('login-form');
  var message = document.getElementById('login-message');
  var password = document.getElementById('password');
  var submit = document.getElementById('login-submit');

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    var passwordValue = password.value.trim();
    message.textContent = '';
    if (!passwordValue) {
      message.textContent = '请输入管理密码';
      password.focus();
      return;
    }
    submit.disabled = true;
    submit.textContent = '登录中...';
    fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: passwordValue })
    }).then(function (response) {
      return response.json();
    }).then(function (body) {
      if (body.success) {
        window.location.href = '/';
      } else {
        if (body.error && body.error.code === 'INVALID_PASSWORD') {
          message.textContent = '密码错误';
        } else {
          message.textContent = '登录服务不可用，请稍后重试';
        }
      }
    }).catch(function () {
      message.textContent = '登录服务不可用，请稍后重试';
    }).then(function () {
      submit.disabled = false;
      submit.textContent = '进入控制台';
    });
  });
})();
