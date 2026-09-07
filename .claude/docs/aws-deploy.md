# TicketRush — AWS 재배포 런북

로컬에서 못 하는 **포트원 실웹훅 검증**과 시연 녹화를 위해 AWS에 배포하는 절차.
2026-09-06 첫 배포는 수동으로 했고 런북이 없었다 — 2026-09-07 재배포부터 이 문서로 정리한다.
스펙 근거는 `aws-spec.md`, 배포 구성 결정은 `decisions.md` 10번.

## 남겨둔 리소스 (재배포마다 재사용, 삭제 안 함)

| 리소스 | 식별자 |
|---|---|
| EC2 보안그룹 | `ticketrush-ec2-sg` = `sg-01d66f91969b40361` (SSH·8080·3000·9090은 개발 PC IP만, 80은 전체) |
| RDS 보안그룹 | `ticketrush-rds-sg` = `sg-0153893907159a0ce` (3306은 EC2 SG에서만) |
| 키페어 | `ticketrush-key` (로컬 `~/.ssh/ticketrush-key.pem`) |
| DB 서브넷 그룹 | `ticketrush-db-subnet-group` |
| DB 파라미터 그룹 | `ticketrush-mysql80` (family mysql8.0, `binlog_format=ROW` / `binlog_row_image=full` — Debezium 필수) |
| IAM 유저 | `ticketrush-deploy` (`aws configure` 완료 상태) |

**개발 PC IP가 바뀌면** SG 인바운드 규칙(22/8080/3000/9090)의 CIDR을 갱신해야 한다:
`aws ec2 authorize-security-group-ingress --group-id sg-01d66f91969b40361 --protocol tcp --port 22 --cidr <새IP>/32` (기존 규칙은 revoke).

## 생성되는 리소스 (측정·녹화 후 삭제)

- RDS `ticketrush-db` (`db.m6i.large`, mysql 8.0.42, gp3 20GB, backup-retention 1 — **0으로 하면 binlog도 꺼져 Debezium이 못 읽는다**, 2026-09-06에 실제로 겪음)
- EC2 `ticketrush-ec2` (`m6i.xlarge`, Amazon Linux 2023, gp3 30GB)

단가(서울): 둘 다 각 $0.236/시간, 합 시간당 약 640원. RDS는 생성 즉시 과금.

## 절차

### Phase 1 — RDS (먼저, ~12분 소요)
```bash
aws rds create-db-instance \
  --db-instance-identifier ticketrush-db --db-instance-class db.m6i.large \
  --engine mysql --engine-version 8.0.42 \
  --master-username admin --master-user-password '<PW>' \
  --allocated-storage 20 --storage-type gp3 \
  --db-subnet-group-name ticketrush-db-subnet-group \
  --vpc-security-group-ids sg-0153893907159a0ce \
  --db-parameter-group-name ticketrush-mysql80 \
  --backup-retention-period 1 \
  --no-multi-az --no-publicly-accessible --no-auto-minor-version-upgrade
# 상태 확인 (available 될 때까지)
aws rds describe-db-instances --db-instance-identifier ticketrush-db \
  --query "DBInstances[0].{s:DBInstanceStatus,e:Endpoint.Address}" --output text
```

### Phase 2 — EC2 (Phase 1 대기 중 병행, ~2분)
```bash
AMI=$(aws ec2 describe-images --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023.*-x86_64" "Name=state,Values=available" \
  --query "sort_by(Images, &CreationDate)[-1].ImageId" --output text)
aws ec2 run-instances --image-id "$AMI" --instance-type m6i.xlarge \
  --key-name ticketrush-key --security-group-ids sg-01d66f91969b40361 \
  --subnet-id <default-subnet> --associate-public-ip-address \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3","DeleteOnTermination":true}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=ticketrush-ec2}]'
```
> `ticketrush-deploy`에는 `ssm:GetParameters` 권한이 없어 AL2023 AMI를 SSM 파라미터로 못 가져온다 → `describe-images`로 조회.

### Phase 3 — EC2 셋업
```bash
ssh -i ~/.ssh/ticketrush-key.pem ec2-user@<EC2_IP>
sudo dnf install -y docker git mariadb105
sudo systemctl enable --now docker && sudo usermod -aG docker ec2-user
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -sL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose && sudo chmod +x $_
git clone https://github.com/2000JCH/TicketRush.git   # 레포 public
```
- **프론트엔드 dist**: 로컬에서 `VITE_API_BASE_URL= npx vite build` (빈 값 → nginx 같은 오리진 `/api/` 상대경로 호출) 후 `scp -r ticketrush-frontend/dist ec2-user@<IP>:~/TicketRush/ticketrush-frontend/dist`. EC2에 node를 안 깐다.
- **`ticketrush-backend/.env`**: 로컬 것을 `scp`. `REFRESH_COOKIE_SECURE=false` (http 데모라 Secure 쿠키 못 씀), 포트원 값 5개 포함.
- **`~/TicketRush/.env`** (레포 루트, compose 변수 치환용) 생성:
  ```
  RDS_ENDPOINT=<rds endpoint>
  RDS_USERNAME=admin
  RDS_PASSWORD=<PW>
  ```

### Phase 4 — DB + 스택 기동
```bash
cd ~/TicketRush
mysql -h $RDS_ENDPOINT -u admin -p'<PW>' -e \
  "CREATE DATABASE IF NOT EXISTS ticketrush CHARACTER SET utf8mb4;
   GRANT REPLICATION CLIENT, REPLICATION SLAVE ON *.* TO 'admin'@'%'; FLUSH PRIVILEGES;"
docker compose -f docker-compose.aws.yml up -d --build   # app 이미지 빌드 ~3분
# health
curl -sf localhost:8080/actuator/health
# Debezium 커넥터 등록 (Kafka에 볼륨 없어 재기동 시마다 필요)
#   register-outbox-connector.ps1 의 JSON을 curl로 POST http://localhost:8083/connectors
curl -s localhost:8083/connectors/ticketrush-outbox-connector/status
```

### Phase 5 — 시드 (계정 6 + 콘서트 5, 로컬과 동일)
`scripts/seed-demo.sh` (또는 scratchpad 버전) 를 EC2에서 실행:
- ADMIN(`admin@ticketrush.com`) 자동 생성됨 → 로그인
- `seed-organizer@ticketrush.test` 가입 → ADMIN 승인 → 로그인 → 콘서트 5개 등록(openAt = now+수분)
- `qqqq`/`wwwww`/`eeee`/`aaaa @naver.com` BUYER 가입
- `aaaa@naver.com` → ADMIN 승격은 API 불가, RDS에서: `UPDATE account SET role='ADMIN' WHERE email='aaaa@naver.com';`

콘서트 5개 (구역·가격·좌석수 로컬과 동일):
| 콘서트 | 구역 |
|---|---|
| 아이유 콘서트 〈더 골든 아워〉 | VIP석 SEATED 154000 5×10 / R석 SEATED 132000 10×15 / 스탠딩 STANDING 99000 x200 |
| 성시경 연말 콘서트 2026 | R석 SEATED 132000 10×20 / S석 SEATED 99000 15×20 |
| BTS 팬미팅 〈러브 마이셀프〉 | 전석 스탠딩 STANDING 88000 x500 |
| 뮤지컬 〈레미제라블〉 서울 | VIP석 SEATED 170000 8×10 / OP석 SEATED 140000 10×12 |
| 잔나비 전국투어 : 서울 | 지정석 SEATED 110000 12×12 / 스탠딩 STANDING 99000 x100 |

### Phase 6 — 포트원 웹훅 URL
포트원 콘솔 → 웹훅 → URL을 `http://<EC2_PUBLIC_DNS>/api/v1/payments/webhook` 로 변경(사용자가 직접).

### Phase 7 — 결제 + 웹훅 실측 (작업 2번)
- 브라우저에서 `http://<EC2_PUBLIC_DNS>` 접속 → 골든 패스 → 홀드 → **카드(토스페이먼츠 테스트)** 결제
- 결제 완료 후: `reservation.status` 가 `PAYMENT_CONFIRMED` 로 바뀌는지, `docker logs ticketrush-app | grep -i webhook` 로 서명 검증 통과 여부 확인
- **Standard Webhooks 가정이 틀리면**: `PaymentWebhookService` 수정 → 로컬 커밋 → EC2에서 `git pull` → `docker compose -f docker-compose.aws.yml up -d --build app` 재배포 반복

## 정리 (측정·녹화 끝난 뒤)
```bash
aws ec2 terminate-instances --instance-ids <id>
aws rds delete-db-instance --db-instance-identifier ticketrush-db --skip-final-snapshot --delete-automated-backups
```
보안그룹·키페어·서브넷그룹·파라미터그룹은 남긴다(다음 재배포용).
