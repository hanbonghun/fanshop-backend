#!/bin/bash
# JWT 토큰 생성 스크립트
# 사용법: ./generate-token.sh [memberId]
# 예시:   ./generate-token.sh 1

MEMBER_ID=${1:-1}
SECRET="fanshop-member-service-jwt-secret-key-must-be-at-least-256bits"

# Node.js로 JWT 생성
TOKEN=$(node -e "
const crypto = require('crypto');

const secret = '$SECRET';
const memberId = '$MEMBER_ID';

function base64url(str) {
  return Buffer.from(str).toString('base64')
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}

const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
const now = Math.floor(Date.now() / 1000);
const payload = base64url(JSON.stringify({
  sub: memberId,
  email: 'test' + memberId + '@fanshop.com',
  iat: now,
  exp: now + 86400
}));

const signature = crypto
  .createHmac('sha256', secret)
  .update(header + '.' + payload)
  .digest('base64')
  .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');

console.log(header + '.' + payload + '.' + signature);
")

echo "memberId=$MEMBER_ID 토큰:"
echo "$TOKEN"
echo ""
echo "환경변수 설정:"
echo "export JWT_TOKEN=\"$TOKEN\""
