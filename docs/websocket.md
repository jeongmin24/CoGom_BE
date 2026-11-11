# CoGom 실시간 스터디룸

네이티브 WebSocket으로 3D 공간의 플레이어 위치, 집중 상태, 완료한 일을 공유합니다. 기본 경로는 /ws/study-room 입니다. 브라우저 오리진은 http://127.0.0.1:5173 과 http://localhost:5173 을 허용합니다.

## 입장과 상태

연결 후 다음 메시지를 보냅니다. 닉네임은 공백 제거 후 1~16자입니다.

~~~json
{"type":"join","name":"지민"}
~~~

서버가 welcome을 응답합니다. selfId는 이 연결의 사용자 ID입니다. 최초 입장 인원이 최대 6명을 넘으면 error가 응답됩니다. 같은 연결에서 join을 다시 보내면 현재 정보를 복원합니다.

~~~json
{
  "type":"welcome",
  "selfId":"server-generated-id",
  "serverNow":1794387600000,
  "players":[
    {
      "id":"server-generated-id",
      "name":"지민",
      "position":[-3,0],
      "rotation":0,
      "status":"resting",
      "remainingSeconds":1500,
      "endsAt":null,
      "seatIndex":0,
      "studyStyle":"typing",
      "styleChangedAt":1794387600000,
      "knockUntil":null,
      "jumpStartedAt":null
    }
  ],
  "todos":[]
}
~~~

다른 사용자 입장과 이동, 타이머 변경, 완료한 일 등록, 퇴장마다 같은 players·todos·serverNow 필드를 가진 room_state 메시지를 받습니다. 모든 시각은 Unix epoch 밀리초입니다. 집중 중 남은 시간은 endsAt에서 보정된 현재 시각을 빼서 계산합니다.

seatIndex는 입장할 때 배정받은 0~5 사이의 좌석 번호입니다. 집중을 시작하면 서버가 해당 좌석 위치와 방향으로 이동시킵니다. 좌석 0~2는 z=0에서 rotation=0, 좌석 3~5는 z=2.4에서 rotation=π이며 각 줄의 x는 -3, 0, 3입니다.

studyStyle은 writing, headphones, thinking, reading, typing 중 하나입니다. 집중 시작 시 이전과 다른 스타일을 선택하고 집중 중에는 약 20초마다 변경합니다. styleChangedAt은 마지막 선택 시각이며 모든 클라이언트가 같은 모션을 선택하는 기준입니다.

## 클라이언트 요청

| type | 필드 | 동작 |
| --- | --- | --- |
| move | position [x,z], rotation | 내 캐릭터 위치와 Y축 회전을 갱신 |
| jump | 없음 | 800ms 길이의 점프 시작 시각을 공유 |
| timer_state | status, remainingSeconds | studying 또는 resting으로 상태 변경 |
| wake_up | targetId | 근처에서 쉬는 사용자에게 알람 전송 |
| todo_complete | text | 완료한 일을 벽면에 공유 |

위치 범위는 x -5~5, z -3~3.5입니다. rotation은 라디안이며 -2π~2π 범위입니다. 이동 요청은 resting 상태이고 knockUntil이 null일 때 처리합니다. 집중 시간은 정수 0~1500초이고 studying에는 1초 이상이 필요합니다. 서버가 endsAt을 계산하고 시간이 끝나면 resting으로 전환해 방송합니다.

## 점프

~~~json
{"type":"jump"}
~~~

서버는 resting 상태이고 노크·점프가 진행 중이지 않을 때만 요청을 수락합니다. 수락하면 jumpStartedAt에 서버의 현재 Unix epoch 밀리초를 지정한 room_state를 방송합니다. 모든 클라이언트는 같은 시작 시각과 고정 길이 800ms로 점프를 재생합니다. 공중에서도 move 요청으로 수평 이동할 수 있지만 다시 점프하거나 친구를 깨우는 요청은 거부합니다. 집중을 시작하면 점프를 취소하고 배정 좌석으로 이동합니다.

서버는 800ms가 지나면 jumpStartedAt을 null로 갱신해 방송합니다. 정기 정리는 최대 500ms 간격으로 실행하므로 클라이언트는 null 메시지를 기다리지 않고 jumpStartedAt + 800 시점에 착지해야 합니다. 모든 새 요청을 처리하기 전에도 만료 상태를 정리하므로 착지 후 다음 점프를 즉시 시작할 수 있습니다.

## 이동과 타이머 예시

~~~json
{"type":"move","position":[-1.5,0],"rotation":1.2}
~~~

~~~json
{"type":"timer_state","status":"studying","remainingSeconds":1500}
~~~

일시 정지할 때 클라이언트는 계산한 현재 잔여 시간을 보냅니다.

~~~json
{"type":"timer_state","status":"resting","remainingSeconds":1230}
~~~

완료한 일의 text는 앞뒤 공백을 제거한 1~70자입니다. 서버는 id, text, authorName, completedAt을 포함한 객체를 todos에 추가하며 최근 12개를 보관합니다.

~~~json
{"type":"todo_complete","text":"로그인 실패 케이스 테스트 완료"}
~~~

## 깨우기

서버는 요청자의 연결 ID로 발신자를 판단합니다. 발신자와 상대방 모두 쉬는 상태인지, 발신자가 점프 중이 아닌지, 두 캐릭터의 수평 거리가 1.8 이하인지 확인합니다. 동일 발신자와 수신자 사이에는 5초의 간격을 둡니다.

요청을 수락하면 발신자의 rotation을 상대방 방향으로 설정하고 knockUntil을 현재 시각에서 1,600ms 뒤로 지정한 room_state를 먼저 방송합니다. 모든 클라이언트는 이 기간 동안 발신자의 노크 애니메이션을 재생합니다. 서버는 기간이 끝나면 knockUntil을 null로 갱신해 방송합니다. 집중을 시작하면 진행 중인 노크를 끝내고 배정 좌석으로 이동합니다.

~~~json
{"type":"wake_up","targetId":"other-player-id"}
~~~

수신자는 다음 이벤트로 토스트와 효과음을 표시합니다.

~~~json
{"type":"wake_up","fromId":"sender-id","fromName":"지민","targetId":"other-player-id"}
~~~

발신자는 전송 확인을 받습니다.

~~~json
{"type":"wake_sent","targetId":"other-player-id","targetName":"민서"}
~~~

## 오류와 연결 종료

형식, 범위, 자리 수, 깨우기 조건 오류는 다음 형식으로 응답합니다.

~~~json
{"type":"error","message":"친구에게 조금 더 가까이 다가가 주세요."}
~~~

연결이 종료되면 플레이어를 제거하고 최신 상태를 방송합니다. 다시 연결하면 새 ID로 입장합니다. 상태는 단일 서버 프로세스 메모리에 보관합니다. 계정 인증과 여러 서버 간 상태 공유는 후속 구현 범위입니다.

## 점프 통합 검증

빈 방을 가진 별도 서버를 18081 포트에 실행한 뒤, 기본 WebSocket을 지원하는 Node.js로 `node scripts/ws-jump-smoke.cjs`를 실행합니다. 다른 포트를 사용하면 `COGOM_WS_URL` 환경 변수에 WebSocket 주소를 지정합니다. 검증 스크립트는 두 클라이언트의 동일 시작 시각, 공중 이동, 중복 점프·공중 깨우기 거부, 착지 후 재점프, 집중 시작 시 취소, 공부·노크 중 잠금을 확인합니다.
