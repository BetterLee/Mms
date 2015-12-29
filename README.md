# Mms
MT6753原生备份

$ git push –u origin/master master
fatal: '–u' does not appear to be a git repository
fatal: Could not read from remote repository.

Please make sure you have the correct access rights
and the repository exists.
以上提示错误后，可用以下命令push
$ git remote add originhttps https://github.com/BetterLee/Mms.git
$ git push originhttps master