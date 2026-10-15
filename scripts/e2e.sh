#!/usr/bin/env bash
# 端到端验收：对运行中的服务做全流程黑盒验证（与 Rust 版行为逐条对照）。
# 用法：BASE=http://127.0.0.1:8080 ./scripts/e2e.sh
set -uo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
API="$BASE/api/v1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0; FAIL=0; FAILED=()
ok()   { PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); FAILED+=("$1 :: $2"); printf '  \033[31m✗\033[0m %s — %s\n' "$1" "$2"; }
section() { printf '\n\033[1m[%s]\033[0m\n' "$1"; }

STATUS=""
req() { # METHOD PATH TOKEN [JSON]
  local method="$1" path="$2" token="${3:-}" data="${4:-}"
  local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-H 'content-type: application/json' --data-binary "$data")
  STATUS=$(curl "${args[@]}")
}
expect_status() { if [ "$STATUS" = "$2" ]; then ok "$1"; else bad "$1" "期望 HTTP $2，实际 $STATUS $(head -c 220 "$TMP/body")"; fi }
expect_eq()     { local got; got=$(jq -r "$2" "$TMP/body" 2>/dev/null); if [ "$got" = "$3" ]; then ok "$1"; else bad "$1" "期望 $2='$3'，实际 '$got'"; fi }
expect_true()   { local got; got=$(jq -r "$2" "$TMP/body" 2>/dev/null); if [ "$got" = "true" ]; then ok "$1"; else bad "$1" "期望 $2=true，实际 '$got'"; fi }
expect_has()    { if grep -q -- "$2" "$TMP/body"; then ok "$1"; else bad "$1" "响应中未找到 '$2'：$(head -c 220 "$TMP/body")"; fi }
n() { jq -r "$1" "$TMP/body" 2>/dev/null; }
note() { printf '  \033[36mi\033[0m %s\n' "$1"; }

[[ -f "$ROOT/.env" ]] && { set -a; . "$ROOT/.env"; set +a; }
ADMIN_PHONE="${ADMIN_PHONE:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
if [ -z "$ADMIN_PHONE" ] || [ -z "$ADMIN_PASSWORD" ]; then
  echo "需要 ADMIN_PHONE / ADMIN_PASSWORD（.env 里配置的管理员引导凭据）" >&2
  exit 2
fi

TS=$(date +%s); SUF="$(( TS % 100000 ))"
P1="138${TS: -6}$(( RANDOM % 90 + 10 ))"; P1="${P1:0:11}"
P2="139${TS: -6}$(( RANDOM % 90 + 10 ))"; P2="${P2:0:11}"
P3="136${TS: -6}$(( RANDOM % 90 + 10 ))"; P3="${P3:0:11}"
P4="137${TS: -6}$(( RANDOM % 90 + 10 ))"; P4="${P4:0:11}"
P5="135${TS: -6}$(( RANDOM % 90 + 10 ))"; P5="${P5:0:11}"
P6="134${TS: -6}$(( RANDOM % 90 + 10 ))"; P6="${P6:0:11}"
P7="133${TS: -6}$(( RANDOM % 90 + 10 ))"; P7="${P7:0:11}"
P8="132${TS: -6}$(( RANDOM % 90 + 10 ))"; P8="${P8:0:11}"

section "系统端点"
req GET /healthz; expect_status "GET /healthz 200" 200; expect_eq "数据库探活 up" '.database' 'up'
req GET /; expect_status "GET / 200" 200; expect_eq "服务名" '.service' 'meow-star-careers'
req GET /nope/nothing; expect_status "未匹配路由统一 404" 404; expect_eq "404 code" '.code' 'not_found'
req POST /api/v1/auth/login "" '{"phone":'; expect_status "非法 JSON -> 400" 400; expect_eq "400 code" '.code' 'bad_request'

section "认证与角色"
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"求职者甲","password":"secret123"}' "$P1")"
expect_status "注册求职者 201" 201; S1=$(n '.token'); S1ID=$(n '.user.id'); expect_eq "默认角色 seeker" '.user.role' 'seeker'
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"求职者乙","password":"secret123"}' "$P2")"
S2=$(n '.token'); S2ID=$(n '.user.id'); expect_status "注册求职者乙 201" 201
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"HR-A","password":"secret123","role":"recruiter","company":{"name":"公司A-%s","industry":"互联网","location":"北京","staffSize":100}}' "$P3" "$SUF")"
expect_status "注册招聘者（含企业、staffSize）201" 201; R1=$(n '.token'); R1ID=$(n '.user.id'); CA=$(n '.user.companyId'); expect_eq "企业规模计入" '.user.role' 'recruiter'
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"HR-B","password":"secret123","role":"recruiter","company":{"name":"公司B-%s","location":"北京","staffSize":100}}' "$P4" "$SUF")"
R2=$(n '.token'); R2ID=$(n '.user.id'); CB=$(n '.user.companyId'); expect_status "注册招聘者B 201" 201
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"HR-C","password":"secret123","role":"recruiter","company":{"name":"公司C-%s","location":"上海","staffSize":20}}' "$P5" "$SUF")"
R3=$(n '.token'); R3ID=$(n '.user.id'); CC=$(n '.user.companyId'); expect_status "注册小规模招聘者C 201" 201
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"重复","password":"secret123"}' "$P1")"
expect_status "重复手机号 409" 409; expect_eq "409 code" '.code' 'conflict'
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"x","password":"secret123","role":"admin"}' "$P6")"
expect_status "admin 不可自助注册 400" 400
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"x","password":"secret123","role":"reviewer"}' "$P6")"
expect_status "reviewer 不可自助注册 400" 400
req POST /api/v1/auth/register "" '{"phone":"12345","name":"x","password":"secret123"}'
expect_status "非法手机号 400" 400
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"x","password":"short"}' "$P6")"
expect_status "密码过短 400" 400
req POST /api/v1/auth/register "" "$(printf '{"phone":"%s","name":"x","password":"secret123","role":"recruiter"}' "$P6")"
expect_status "招聘者缺 company 400" 400
req POST /api/v1/auth/login "" "$(printf '{"phone":"%s","password":"secret123"}' "$P1")"
expect_status "登录 200" 200; S1B=$(n '.token')
req POST /api/v1/auth/login "" "$(printf '{"phone":"%s","password":"wrong-password"}' "$P1")"
expect_status "密码错误 401" 401
req POST /api/v1/auth/login "" '{"phone":"13000000000","password":"whatever123"}'
expect_status "账号不存在也是 401（不区分）" 401
req GET /api/v1/auth/me "$S1"; expect_status "GET /auth/me 200" 200; expect_eq "手机号一致" '.phone' "$P1"
req GET /api/v1/auth/me ""; expect_status "缺令牌 401" 401; expect_eq "401 message" '.message' 'missing bearer token'
req GET /api/v1/auth/me "forged-token"; expect_status "伪造令牌 401" 401
req POST /api/v1/auth/logout "$S1B"; expect_status "登出 204" 204
req GET /api/v1/auth/me "$S1B"; expect_status "登出后令牌失效 401" 401
req POST /api/v1/auth/login "" "$(printf '{"phone":"%s","password":"admin-secret-123"}' "$ADMIN_PHONE")"
expect_status "管理员登录 200" 200; AD=$(n '.token'); ADID=$(n '.user.id'); expect_eq "角色 admin" '.user.role' 'admin'

section "管理员引导幂等"
req POST /api/v1/auth/login "" "$(printf '{"phone":"%s","password":"%s"}' "$ADMIN_PHONE" "$ADMIN_PASSWORD")"
expect_status "管理员密码未被启动改写 200" 200

section "用户管理"
req GET /api/v1/users "$S1"; expect_status "非管理员列表 403" 403
req GET /api/v1/users "$AD"; expect_status "管理员列表 200" 200
expect_true "列表含求职者甲" "(if type == \"array\" then . else .users end) | map(.id) | index(\"$S1ID\") != null"
req GET "/api/v1/users/$S2ID" "$S1"; expect_status "查他人 404（防探测）" 404
req GET "/api/v1/users/$S1ID" "$S1"; expect_status "本人查询 200" 200
req PUT "/api/v1/users/$S1ID" "$S1" '{"name":"求职者甲-改名"}'; expect_status "本人修改 200" 200; expect_eq "改名生效" '.name' '求职者甲-改名'
req DELETE "/api/v1/users/$S2ID" "$S1"; expect_status "非管理员删除 403" 403
req POST /api/v1/users "$AD" "$(printf '{"email":"maker-%s@example.com","name":"历史邮箱账号"}' "$SUF")"
expect_status "管理员创建用户 201" 201; U9=$(n '.id')
req DELETE "/api/v1/users/$U9" "$AD"; expect_status "管理员删除用户 204" 204

section "审核账号（仅 admin）"
req POST /api/v1/reviewers "$AD" "$(printf '{"phone":"%s","name":"审核员甲","password":"review-pass-1"}' "$P7")"
expect_status "创建审核账号 201" 201; RV1=$(n '.id'); expect_eq "角色 reviewer" '.role' 'reviewer'
req POST /api/v1/reviewers "$AD" "$(printf '{"phone":"%s","name":"审核员乙","password":"review-pass-2"}' "$P8")"
expect_status "创建第二个审核账号 201" 201; RV2=$(n '.id')
P9="131${TS: -6}$(( RANDOM % 90 + 10 ))"; P9="${P9:0:11}"
req POST /api/v1/reviewers "$AD" "$(printf '{"phone":"%s","name":"审核员丙","password":"review-pass-3"}' "$P9")"
expect_status "创建第三个审核账号 201" 201; RV3=$(n '.id')
req GET /api/v1/reviewers "$AD"; expect_status "管理员列表 200" 200
req POST /api/v1/auth/login "" "$(printf '{"phone":"%s","password":"review-pass-1"}' "$P7")"
expect_status "审核账号可登录 200" 200; RT1=$(n '.token')
req POST /api/v1/auth/login "" "$(printf '{"phone":"%s","password":"review-pass-2"}' "$P8")"
RT2=$(n '.token'); expect_status "审核账号乙可登录 200" 200
req GET /api/v1/reviewers "$RT1"; expect_status "审核账号访问 /reviewers 403" 403
req POST "/api/v1/reviewers/$RV3/password" "$AD" '{"password":"review-pass-3b"}'
expect_status "重置密码 204" 204
req POST /api/v1/auth/login "" "$(printf '{"phone":"%s","password":"review-pass-3"}' "$P9")"
expect_status "重置后旧密码失效 401" 401
req POST /api/v1/auth/login "" "$(printf '{"phone":"%s","password":"review-pass-3b"}' "$P9")"
expect_status "重置后新密码可登录 200" 200
req DELETE "/api/v1/reviewers/$RV3" "$AD"; expect_status "删除审核账号 204" 204

section "职位"
req POST /api/v1/jobs "$S1" '{"title":"hack","description":"hack"}'; expect_status "求职者发职位 403" 403
req POST /api/v1/jobs "$R1" "$(printf '{"title":"后端工程师-%s","description":"云原生平台后端","requirements":"Java/Rust","location":"北京","salaryMin":20000,"salaryMax":35000,"jobType":"full_time","experience":"3-5年","education":"本科"}' "$SUF")"
expect_status "发布职位 201" 201; JA=$(n '.id')
req POST /api/v1/jobs "$R2" "$(printf '{"title":"前端工程师-%s","description":"Web 前端","location":"北京","salaryMin":15000,"salaryMax":25000,"jobType":"full_time"}' "$SUF")"
expect_status "发布职位B 201" 201; JB=$(n '.id')
req POST /api/v1/jobs "$R3" "$(printf '{"title":"产品经理-%s","description":"B 端产品","location":"上海","salaryMin":18000,"salaryMax":28000,"jobType":"full_time"}' "$SUF")"
expect_status "发布职位C（小规模企业）201" 201; JCJOB=$(n '.id')
req POST /api/v1/jobs "$R1" '{"title":"x","description":"y","jobType":"bogus"}'; expect_status "非法 jobType 400" 400
req POST /api/v1/jobs "$R1" '{"title":"x","description":"y","salaryMin":30000,"salaryMax":10000}'; expect_status "薪资区间倒挂 400" 400
req POST /api/v1/jobs "$R1" '{"title":"","description":"y"}'; expect_status "标题为空 400" 400
req GET "/api/v1/jobs?keyword=$SUF" "$S1"; expect_status "搜索 200" 200
expect_true "搜索结果含职位A" "[.items[].id] | index(\"$JA\") != null"
expect_true "搜索结果含职位B" "[.items[].id] | index(\"$JB\") != null"
req GET "/api/v1/jobs?keyword=$SUF&location=%E5%8C%97%E4%BA%AC&jobType=full_time&salaryMin=10000&salaryMax=40000" "$S1"
expect_status "组合过滤 200" 200
req GET "/api/v1/jobs?keyword=$SUF&location=%E4%B8%8D%E5%AD%98%E5%9C%A8%E5%9F%8E%E5%B8%82" "$S1"; expect_status "城市过滤（编码）200" 200; expect_eq "过滤后为空" '.total' '0'
req GET "/api/v1/jobs?keyword=$SUF&location=%E5%8C%97%E4%BA%AC" "$S1"; expect_status "百分号编码查询 200" 200
# 已知差异（超集）：未转义的非 ASCII URL —— Rust actix 返回 400，Java(Undertow) 返回 200
RAW=$(curl -s -o /dev/null -w '%{http_code}' "$API/jobs?location=北京" -H "Authorization: Bearer $S1")
note "未转义非 ASCII URL（location=北京）：本服务返回 ${RAW}（Rust 版为 400；Java 为超集，见 README 差异说明）"
req GET "/api/v1/jobs/$JA" "$S1"; expect_status "职位详情 200" 200
expect_eq "未投诉时等级优秀" '.complaintLevel' 'excellent'
expect_eq "有规模用率口径" '.complaintBasis' 'rate'
expect_eq "透出企业规模" '.companyStaffSize' '100'
req PUT "/api/v1/jobs/$JA" "$R1" "$(printf '{"title":"后端工程师v2-%s"}' "$SUF")"; expect_status "编辑职位 200" 200; expect_eq "标题已改" '.title' "后端工程师v2-$SUF"
req PUT "/api/v1/jobs/$JA" "$R2" '{"title":"越权"}'; expect_status "跨企业编辑 403" 403
req POST "/api/v1/jobs/$JA/active" "$R1" '{"isActive":false}'; expect_status "下架 200" 200; expect_eq "已下架" '.isActive' 'false'
req GET "/api/v1/jobs/$JA" "$S1"; expect_status "下架后求职者看不到 404" 404
req GET "/api/v1/jobs/$JA" "$R1"; expect_status "本企业仍可见 200" 200
req POST "/api/v1/jobs/$JA/active" "$R1" '{"isActive":true}'; expect_status "上架 200" 200
req GET "/api/v1/jobs/my?pageSize=50" "$R1"; expect_status "我的职位 200" 200
expect_true "我的职位含A" "[.items[].id] | index(\"$JA\") != null"
req POST "/api/v1/jobs/$JB/save" "$S1"; expect_status "收藏 204" 204
req GET "/api/v1/saved-jobs?pageSize=50" "$S1"; expect_status "我的收藏 200" 200
expect_true "收藏含B" "[.items[].id] | index(\"$JB\") != null"
req POST "/api/v1/jobs/$JB/unsave" "$S1"; expect_status "取消收藏 204" 204
req GET "/api/v1/saved-jobs?pageSize=50" "$S1"; expect_status "收藏列表 200" 200
expect_true "已取消收藏" "[.items[].id] | index(\"$JB\") == null"
req POST "/api/v1/jobs/$JA/save" "$R1"; expect_status "收藏无角色门禁（招聘者也可）204" 204

section "简历"
req POST /api/v1/resumes "$R1" '{"fullName":"x","title":"y"}'; expect_status "招聘者建简历 403" 403
req POST /api/v1/resumes "$S1" "$(printf '{"fullName":"求职者甲","title":"后端工程师","phone":"%s","years":5,"education":"本科","skills":"Java,Rust","summary":"全栈经验","isPublic":true}' "$P1")"
expect_status "创建简历 201" 201; RES1=$(n '.id'); expect_true "公开简历" '.isPublic'
req GET /api/v1/resumes "$S1"; expect_status "我的简历 200" 200
req GET "/api/v1/resumes/$RES1" "$S2"; expect_status "他人私有/越权 404" 404
req PUT "/api/v1/resumes/$RES1" "$S1" "$(printf '{"fullName":"求职者甲","title":"高级后端工程师","phone":"%s","years":5,"education":"本科","skills":"Java,Rust","summary":"全栈经验","isPublic":true}' "$P1")"; expect_status "更新简历 200" 200; expect_eq "标题已改" '.title' '高级后端工程师'
req PUT "/api/v1/resumes/$RES1" "$S1" '{"title":"只给标题"}'; expect_status "局部更新缺少 fullName -> 400（与 Rust validate_resume 一致）" 400
req GET "/api/v1/resumes/search?keyword=Java" "$R1"; expect_status "招聘者检索公开简历 200" 200
expect_true "检索命中公开简历" "[.items[].id] | index(\"$RES1\") != null"
req GET "/api/v1/resumes/search?keyword=Java" "$S1"; expect_status "求职者检索 403" 403

section "投递"
req POST "/api/v1/jobs/$JA/apply" "$R1" '{"coverLetter":"我是 HR"}'; expect_status "招聘者投递 403" 403
req POST "/api/v1/jobs/$JA/apply" "$S1" "$(printf '{"resumeId":"%s","coverLetter":"你好，我想应聘"}' "$RES1")"
expect_status "投递 201" 201; APP1=$(n '.id'); expect_eq "初始状态 pending" '.status' 'pending'
req POST "/api/v1/jobs/$JA/apply" "$S1" '{"coverLetter":"再来一次"}'; expect_status "一人一岗一次 409" 409
req GET "/api/v1/applications?pageSize=50" "$S1"; expect_status "我的投递 200" 200
expect_true "投递列表含 APP1" "[.items[].id] | index(\"$APP1\") != null"
req GET "/api/v1/applications/$APP1" "$R1"; expect_status "招聘者看投递详情 200" 200; expect_eq "可见求职者手机号" '.seekerPhone' "$P1"
req GET "/api/v1/applications/$APP1" "$S2"; expect_status "无关用户看投递 403" 403
req POST "/api/v1/applications/$APP1/status" "$S1" '{"status":"interviewing"}'; expect_status "求职者不能推进 403" 403
req POST "/api/v1/applications/$APP1/status" "$R2" '{"status":"viewed"}'; expect_status "跨企业推进 403" 403
req POST "/api/v1/applications/$APP1/status" "$R1" '{"status":"bogus"}'; expect_status "非法状态 400" 400
req POST "/api/v1/applications/$APP1/status" "$R1" '{"status":"viewed"}'; expect_status "推进 viewed 200" 200; expect_eq "状态已变" '.status' 'viewed'
req POST "/api/v1/applications/$APP1/status" "$R1" '{"status":"interviewing"}'; expect_status "推进 interviewing 200" 200

section "私聊（REST）"
req POST /api/v1/conversations "$S1" "$(printf '{"userId":"%s"}' "$R1ID")"
expect_status "发起会话 200" 200; CONV=$(n '.id'); expect_eq "对端姓名" '.peer.name' 'HR-A'
req POST /api/v1/conversations "$S1" "$(printf '{"userId":"%s"}' "$R1ID")"
expect_status "重复发起返回同一会话 200" 200; expect_eq "会话 id 不变" '.id' "$CONV"
req GET /api/v1/conversations "$S1"; expect_status "会话列表 200" 200
req GET "/api/v1/conversations/$CONV" "$S2"; expect_status "非成员看会话 403/404" 403
req POST "/api/v1/conversations/$CONV/messages" "$S1" '{"body":"你好，约个面试时间？"}'
expect_status "发消息 201" 201; MSG1=$(n '.id'); expect_eq "消息内容" '.content' '你好，约个面试时间？'
req POST "/api/v1/conversations/$CONV/messages" "$S1" "$(printf '{"body":"%s"}' "$(python3 -c 'print("x"*2001)')")"
expect_status "超长消息 400" 400
req GET "/api/v1/conversations/$CONV/messages" "$R1"; expect_status "消息列表 200" 200
req POST "/api/v1/conversations/$CONV/read" "$R1"; expect_status "标记已读 200" 200
req POST "/api/v1/conversations/$CONV/messages" "$S2" '{"body":"越权发消息"}'; expect_status "非成员发消息 403" 403
# 与公司C的会话（投诉前置条件用）
req POST /api/v1/conversations "$S1" "$(printf '{"userId":"%s"}' "$R3ID")"
CONV_C=$(n '.id'); req POST "/api/v1/conversations/$CONV_C/messages" "$S1" '{"body":"公司C在招什么岗位？"}'
expect_status "与公司C沟通成功 201" 201

section "线上面试"
req POST /api/v1/interviews "$S1" "$(printf '{"applicationId":"%s"}' "$APP1")"; expect_status "求职者发起面试 403" 403
req POST /api/v1/interviews "$R1" "$(printf '{"applicationId":"%s"}' "$APP1")"
expect_status "招聘者发起即时面试 201" 201; IV1=$(n '.id'); expect_eq "初始状态 invited" '.status' 'invited'
req GET /api/v1/interviews "$S1"; expect_status "我参与的面试 200" 200
req GET "/api/v1/interviews/$IV1" "$S2"; expect_status "无关用户看面试 403" 403
req POST "/api/v1/interviews/$IV1/start" "$R1"; expect_status "开始面试 200" 200
req POST "/api/v1/interviews/$IV1/finish" "$S1"; expect_status "结束面试 200" 200; expect_eq "已结束" '.status' 'finished'
req POST "/api/v1/interviews" "$R1" "$(printf '{"applicationId":"%s","scheduledAt":"2030-01-01T10:00:00Z"}' "$APP1")"
expect_status "预约面试 201" 201; IV2=$(n '.id')
req POST "/api/v1/interviews/$IV2/cancel" "$R1"; expect_status "取消面试 200" 200; expect_eq "已取消" '.status' 'cancelled'
req POST "/api/v1/interviews/$IV2/start" "$R1"; expect_status "已取消不能开始 400/409" 400

section "投诉与审核"
req POST "/api/v1/companies/$CC/complaints" "$S2" '{"evidence":"这是一段足够长的投诉证据说明文字，用于满足最少二十个字符的要求。"}'
expect_status "未沟通即投诉 403" 403
req POST "/api/v1/companies/$CA/complaints" "$R1" '{"evidence":"这是一段足够长的投诉证据说明文字，用于满足最少二十个字符的要求。"}'
expect_status "招聘者不能投诉 403" 403
req POST "/api/v1/companies/$CA/complaints" "$S1" '{"evidence":"太短"}'; expect_status "证据过短 400" 400
req POST "/api/v1/companies/$CA/complaints" "$S1" '{"evidence":"公司A在招聘过程中存在虚假承诺，面试流程与描述严重不符，且多次联系不上HR，希望平台核实处理。"}'
expect_status "发起投诉 201" 201; CMP1=$(n '.id'); expect_eq "初始待审" '.status' 'pending'
req GET /api/v1/complaints/mine "$S1"; expect_status "我发起的投诉 200" 200
req GET /api/v1/complaints "$RT1"; expect_status "审核账号看全部 200" 200
req GET "/api/v1/complaints?status=pending" "$RT1"; expect_status "按状态过滤 200" 200
req GET /api/v1/complaints "$R1"; expect_status "招聘者看本企业 200" 200
req GET /api/v1/complaints "$S1"; expect_status "求职者看本人 200" 200
req POST "/api/v1/complaints/$CMP1/claim" "$S1"; expect_status "业务角色不能认领 403" 403
req POST "/api/v1/complaints/$CMP1/claim" "$RT1"; expect_status "审核甲认领 200" 200
expect_eq "持锁人是甲" '.lockedByName' '审核员甲'; expect_true "锁有效" '.lockActive'
req POST "/api/v1/complaints/$CMP1/claim" "$RT2"; expect_status "他人认领 409" 409; expect_has "报错含持锁人" '审核员甲'
req POST "/api/v1/complaints/$CMP1/review" "$RT2" '{"approved":true}'; expect_status "他人持锁时审结 409" 409
req POST "/api/v1/complaints/$CMP1/review" "$RT1" '{"approved":false,"note":"证据不足，驳回"}'
expect_status "驳回 200" 200; expect_eq "已驳回" '.status' 'rejected'; expect_eq "留痕审核人" '.reviewedByName' '审核员甲'
req POST "/api/v1/complaints/$CMP1/review" "$RT1" '{"approved":true}'; expect_status "重复审核 409" 409
req GET "/api/v1/companies/$CA" "$S1"; expect_status "企业详情 200" 200
expect_eq "驳回不累计投诉次数" '.complaintsCount' '0'; expect_eq "企业规模透出" '.staffSize' '100'
req POST "/api/v1/companies/$CA/complaints" "$S1" '{"evidence":"第二次投诉：同一岗位反复撤下又上架，疑似虚假招聘，请平台核实并处理。"}'
CMP2=$(n '.id'); expect_status "第二次投诉 201" 201
req POST "/api/v1/complaints/$CMP2/claim" "$RT1"; expect_status "认领第二次投诉 200" 200
req POST "/api/v1/complaints/$CMP2/review" "$RT1" '{"approved":true,"note":"证据充分"}'
expect_status "通过审核 200" 200; expect_eq "已通过" '.status' 'approved'
req GET "/api/v1/companies/$CA" "$S1"; expect_eq "通过后投诉次数 +1" '.complaintsCount' '1'
# 公司C：小规模 -> 次数口径
req POST "/api/v1/companies/$CC/complaints" "$S1" '{"evidence":"公司C规模虽小但同样存在虚假招聘行为，面试前后说法不一致，请平台核实。"}'
CMP3=$(n '.id'); expect_status "投诉公司C 201" 201
req POST "/api/v1/complaints/$CMP3/claim" "$RT2"; expect_status "审核乙认领 200" 200
req POST "/api/v1/complaints/$CMP3/review" "$RT2" '{"approved":true}'; expect_status "审核乙通过 200" 200
# 释放锁规则
req POST "/api/v1/companies/$CA/complaints" "$S1" '{"evidence":"第三次投诉：用于验证认领锁释放与强制解锁规则，请平台按流程处理。"}'
CMP4=$(n '.id'); expect_status "第三次投诉 201" 201
req POST "/api/v1/complaints/$CMP4/claim" "$RT1"; expect_status "甲认领 200" 200
req POST "/api/v1/complaints/$CMP4/release" "$RT2"; expect_status "他人释放 403" 403
req POST "/api/v1/complaints/$CMP4/release?force=true" "$RT1"; expect_status "非管理员 force 403" 403
req POST "/api/v1/complaints/$CMP4/release" "$RT1"; expect_status "本人释放 200" 200
req POST "/api/v1/complaints/$CMP4/claim" "$RT2"; expect_status "释放后可被他人认领 200" 200
req POST "/api/v1/complaints/$CMP4/release?force=true" "$AD"; expect_status "管理员强制解锁 200" 200
req GET /api/v1/complaint-rules "$S1"; expect_status "定级规则 200" 200
expect_eq "规则版本 v2" '.version' 'v2'; expect_eq "最小规模 50" '.minStaffSizeForRate' '50'
expect_eq "等级序列" '[.levels[]] | join(",")' 'excellent,minor,alert,warning,severe'
expect_eq "率阈值" '[.rateThresholds[]] | join(",")' '0.5,1.5,3.0'
expect_eq "次数阈值" '[.countThresholds[]] | join(",")' '0,1,3,6,10'

section "投诉定级 v2 与职位排序"
req GET "/api/v1/jobs?keyword=$SUF&pageSize=50" "$S1"; expect_status "搜索 200" 200
JC=$(n "[.items[] | select(.companyId == \"$CC\")][0].id")
expect_eq "公司A职位口径为率" "[.items[] | select(.id == \"$JA\")][0].complaintBasis" 'rate'
expect_eq "公司A职位等级预警" "[.items[] | select(.id == \"$JA\")][0].complaintLevel" 'alert'
expect_eq "公司A每百人投诉率 1.0%" "[.items[] | select(.id == \"$JA\")][0].complaintRatePercent" '1.0'
expect_eq "公司C回退次数口径" "[.items[] | select(.id == \"$JC\")][0].complaintBasis" 'count'
expect_eq "公司C等级轻微" "[.items[] | select(.id == \"$JC\")][0].complaintLevel" 'minor'
expect_eq "次数口径下率为 null" "[.items[] | select(.id == \"$JC\")][0].complaintRatePercent" 'null'
POS_B=$(n "[.items[].id] | index(\"$JB\")"); POS_A=$(n "[.items[].id] | index(\"$JA\")")
if [ "$POS_B" -lt "$POS_A" ] 2>/dev/null; then ok "投诉多的企业排在后面（全局有序）"; else bad "投诉多的企业排在后面（全局有序）" "B@$POS_B A@$POS_A"; fi
req GET "/api/v1/jobs?keyword=$SUF&page=1&pageSize=1" "$S1"; POS_P1=$(n '.items[0].id')
req GET "/api/v1/jobs?keyword=$SUF&page=2&pageSize=1" "$S1"; POS_P2=$(n '.items[0].id')
if [ "$POS_P1" != "$POS_P2" ] && [ "$POS_P1" = "$JB" ]; then ok "翻页全局有序（第二页不会出现更优等级）"; else bad "翻页全局有序" "p1=$POS_P1 p2=$POS_P2"; fi

section "平台统计"
req GET /api/v1/stats/review "$AD"; expect_status "管理员审核统计 200" 200
expect_eq "及时线 24h" '.slaSeconds' '86400'
expect_true "管理员看到全部审核账号" "[.reviewers[].reviewerId] | length >= 2"
expect_eq "不返回结论类字段（approved）" '(.reviewers[0] | has("approved"))' 'false'
expect_eq "不返回结论类字段（rejected）" '(.reviewers[0] | has("rejected"))' 'false'
req GET /api/v1/stats/review "$RT1"; expect_status "审核账号统计 200" 200
expect_eq "审核账号只看自己" '[.reviewers[].reviewerId] | join(",")' "$RV1"
req GET /api/v1/stats/review "$S1"; expect_status "业务角色 403" 403
req GET /api/v1/stats/review ""; expect_status "未认证 401" 401
req GET /api/v1/stats/companies "$AD"; expect_status "企业优劣统计 200" 200
IDX_A=$(n "[.[].companyId] | index(\"$CA\")"); IDX_B=$(n "[.[].companyId] | index(\"$CB\")")
if [ "$IDX_A" -lt "$IDX_B" ] 2>/dev/null; then ok "最差在前（公司A 排在公司B 之前）"; else bad "最差在前" "A@$IDX_A B@$IDX_B"; fi
expect_eq "公司A质量分 100-1*10-1*2" ".[] | select(.companyId == \"$CA\") | .qualityScore" '88'
expect_eq "公司A等级序 1（stats 用次数口径，与 Rust 同源）" ".[] | select(.companyId == \"$CA\") | .levelRank" '1'
expect_eq "公司A已核实投诉 1" ".[] | select(.companyId == \"$CA\") | .complaintsApproved" '1'
expect_true "公司A存在待审投诉（质量分口径）" ".[] | select(.companyId == \"$CA\") | .complaintsPending == 1"
req GET /api/v1/stats/companies "$RT1"; expect_status "非管理员 403" 403
req GET /api/v1/stats/seekers "$AD"; expect_status "求职用户统计 200" 200
expect_true "统计含求职者规模" '.overview.total >= 2'
expect_true "近 12 个月趋势" '(.registrationsByMonth | length) >= 1'
expect_true "参与度字段存在" '.engagementRate != null'
req GET /api/v1/stats/seekers "$S1"; expect_status "非管理员 403" 403

section "推送令牌"
req POST /api/v1/push-tokens "$S1" '{"platform":"android","token":"device-token-e2e-1"}'; expect_status "注册推送令牌 204" 204
req POST /api/v1/push-tokens "$S1" '{"platform":"bogus","token":"x"}'; expect_status "非法 platform 400" 400
req POST /api/v1/push-tokens "$S1" '{"platform":"ios","token":""}'; expect_status "空 token 400" 400
req POST /api/v1/push-tokens/remove "$S1" '{"platform":"android","token":"device-token-e2e-1"}'; expect_status "注销推送令牌 204" 204

section "文档 / CORS / 体积上限"
req GET /api-docs/openapi.json; expect_status "OpenAPI 规范 200" 200; expect_has "含 bearerAuth" 'bearerAuth'
expect_has "标题与版本" 'meow-star-careers API'
CORS1=$(curl -s -o /dev/null -D - -X OPTIONS "$API/auth/login" -H 'Origin: http://localhost:8088' -H 'Access-Control-Request-Method: POST' | tr -d '\r' | grep -i '^access-control-allow-origin' || true)
if [ -n "$CORS1" ]; then ok "白名单来源放行（${CORS1}）"; else bad "白名单来源放行" "缺少 Access-Control-Allow-Origin"; fi
CORS2=$(curl -s -o /dev/null -D - -X OPTIONS "$API/auth/login" -H 'Origin: http://evil.example' -H 'Access-Control-Request-Method: POST' | tr -d '\r' | grep -i '^access-control-allow-origin' || true)
if [ -z "$CORS2" ]; then ok "未授权来源默认拒绝跨域"; else bad "未授权来源默认拒绝跨域" "$CORS2"; fi
UI=$(curl -s -L -o "$TMP/ui" -w '%{http_code}' "$BASE/swagger-ui/"); if [ "$UI" = "200" ] && grep -q 'Swagger UI' "$TMP/ui"; then ok "Swagger UI 可访问"; else bad "Swagger UI 可访问" "HTTP $UI"; fi
python3 -c 'print("{\"phone\":\"" + "1"*1200000 + "\"}")' > "$TMP/big.json"
STATUS=$(curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$API/auth/login" -H 'content-type: application/json' --data-binary "@$TMP/big.json")
expect_status "请求体超过 1 MiB -> 400" 400

section "WebSocket 实时通道"
# WebSocket 端口从 BASE 推导（默认 8080），避免连到别的实例
WS_HOST=$(printf '%s' "$BASE" | sed -E 's#^https?://([^:/]+).*#\1#')
WS_PORT=$(printf '%s' "$BASE" | sed -E 's#^https?://[^:/]+:([0-9]+).*#\1#')
case "$WS_PORT" in ''|*[!0-9]*) WS_PORT=80 ;; esac
if python3 "$ROOT/scripts/ws_e2e.py" --host "$WS_HOST" --port "$WS_PORT" \
     --seeker-token "$S1" --recruiter-token "$R1" \
     --conversation-id "$CONV" --interview-id "$IV1"; then
  PASS=$((PASS+1))
else
  FAIL=$((FAIL+1)); FAILED+=("WebSocket 协议 :: 见上方明细")
fi

printf '\n\033[1m===== 汇总：通过 %d 项，失败 %d 项 =====\033[0m\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf '\n失败明细：\n'
  for item in "${FAILED[@]}"; do printf '  - %s\n' "$item"; done
  exit 1
fi
printf '全部通过 ✅\n'
