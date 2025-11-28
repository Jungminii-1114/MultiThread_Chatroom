package Chatroom;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest; // 암호화를 위한 도구라고 보면 된다.
import java.security.SecureRandom; // 보안성이 강한 랜덤 숫자를 생성하기 위해 import한다.
import java.util.HashMap;// 데이터를 Key-Value(ID-Info) 쌍으로 저장하는 자료구조 HashMap import.
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatServer {

    // 접속한 클라이언트 관리 (ID -> PrintWriter)
    // HashMap으로 "ID'를 주면 그 사람의 PrintWriter를 준다.
    // 모든 클라이언트 핸들러들이 이 하나의 장부를 공유해야 하기 때문에 static으로 설정한다.
    private static Map<String, PrintWriter> activeClients = new HashMap<>();

    // 파일 및 데이터 동기화 락
    // 여러 스레드가 동시에 파일에 접근하거나 쓰면 파일이 깨질 수도 있다.
    // 그래서 이 fileLock을 가진 사람만 파일 작업을 하도록 제한하기 위해 만든 객체이다.
    private static final Object fileLock = new Object();
    // 회원정보를 저장하기 위한 파일 이름을 설정한다.
    private static final String USER_FILE = "users.dat";

    // Main 메소드
    // 1. 스레드 풀 생성 : 접속자가 몰려도 서버가 다운되지 않도록 500개의 스레드로 제한을 둔다.
    // 2. 서버 소켓 생성 : 59001 포트를 점유하고 클라이언트의 연결을 기다린다.
    // 3. 무한 루프 : listener.accpet()로 대기하다가 연결이 들어오면
    //              해당 소켓을 처리할 "Handler" 객체를 만들어서 스레프 풀에 던진다. (비동기처리)
    // # 비동기 처리란?

    public static void main(String[] args) throws Exception {
        System.out.println(">>> 채팅 서버가 실행 중입니다 (Port: 59001) <<<");
        ExecutorService pool = Executors.newFixedThreadPool(500);

        try (ServerSocket listener = new ServerSocket(59001)) {
            while (true) {
                pool.execute(new Handler(listener.accept()));
                // listener.accpet() : 여기서 프로그램이 잠깐 멈춘다. Blocking...
                // 누군가 접속할 때까지 기다리다가 접속하면 Socket 객체를 하나 만들어서 반환한다.

                // poo.execute는 연결된 소켓을 가지고 "Handler"라는 상담원에게 넘겨준다.
                // 그리고 메인 스레드는 즉시 다시 while문 처음으로 돌아가서 다음 손님을 기다린다.
            }
        }
    }

    // --- [접속자 명단 브로드캐스트] (New!) ---
    // 현재 접속 중인 모든 사용자의 ID를 콤마(,)로 구분해서 클라이언트들에게 전송한다.
    // 목적 : Client GUI를 실시간으로 갱신하기 위해서이다.
    // activeClients 맵을 순회하는 동안 다른 스레드가 수정하지 못하도록 'synchronized'를 건다.
    private static void broadcastUserList() {
        StringBuilder sb = new StringBuilder("/userlist ");
        synchronized (activeClients) {
            // 맵에 저장된 모든 Key(사용자 ID)를 콤마(,)로 이어 붙인다.
            for (String user : activeClients.keySet()) {
                sb.append(user).append(",");
            }
            // 모든 클라이언트에게 전송
            // 완성된 명단 문자열을 접속 중인 모든 사용자에게 쏘아준다.
            for (PrintWriter writer : activeClients.values()) {
                writer.println(sb.toString());
            }
        }
    }

    // --- [회원가입 및 인증 로직] (기존 유지) ---
    // 사용자로부터 받은 정보 (ID, 비번, 이름, 이메일)를 파일에 저장합니다.
    // 1. 동기화 : 여러 명이 동시에 가입할 때 파일이 깨지지 않도록 'fileLock'을 건다.
    // 2. 중복 검사 : 파일을 읽어 이미 존재하는 ID인지 확인한다.
    // 3. 보안 저장 : 비밀번호는 그냥 저장하지 않고 Salt를 섞어 해시한 뒤 저장한다.
    private static boolean registerUser(String userID, String password, String name, String email) {
        synchronized (fileLock) {
            // 파일을 읽어서 중복 ID를 체크한다.
            try (BufferedReader br = new BufferedReader(new FileReader(USER_FILE))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",", 5);
                    if (parts.length > 0 && parts[0].equals(userID)) {
                        return false; // ID 중복
                    }
                }
            } catch (FileNotFoundException e) {
                // 파일 없으면 새로 만들면 되므로 무시한다.
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            // 중복이 없으면 파일 끝에 새 정보를 추가한다.
            try (PrintWriter writer = new PrintWriter(new FileWriter(USER_FILE, true))) {
                String salt = getSalt();
                String hashedPassword = getHashedPassword(password, salt); // 암호화 (Hashing)
                // CSV형태로 콤마로 묶어서 한 줄로 기록한다.
                String newUserLine = String.join(",", userID, hashedPassword, salt, name, email);
                writer.println(newUserLine);
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    // 로그인 인증 로직
    // 사용자가 입력한 ID와 비밀번호가 맞는지 확인한다.
    // 1. 파일 탐색 : 해당 ID를 가진 줄을 찾는다.
    // 2. 해시 검증 : 파일에 저장된 Salt를 꺼내서 사용자가 입력한 비밀번호와 섞어 다시 해시를 만든다.
    // 3. 비교 : 방금 만든 해시값과 파일에 저장된 해시값이 같으면 통과(True)이다.
    private static boolean authenticateUser(String userID, String password) {
        synchronized (fileLock) {
            try (BufferedReader br = new BufferedReader(new FileReader(USER_FILE))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",", 5);
                    // ID가 일치하는 라인 발견했을 때
                    if (parts.length >= 3 && parts[0].equals(userID)) {
                        String storedHash = parts[1]; // 저장된 암호문
                        String storedSalt = parts[2]; // 저장된 Salt
                        // 입력된 비밀번호를 똑같은 방식으로 암호화해보고 일치하는지 확인한다.
                        if (getHashedPassword(password, storedSalt).equals(storedHash)) {
                            return true;
                        } else {
                            return false;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false; // ID가 없거나 에러 발생 시에 실패한다.
    }


    // Slat 생성 로직
    // 비밀번호 보안을 강화하기 위히 랜덤한 바이트 배열 (Salt)를 생성한다.
    // 같은 비밀번호라도 Salt가 다르면 저장되는 해시값이 달라져서 해킹이 어려워진다.
    private static String getSalt() throws Exception {
        SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
        byte[] salt = new byte[16];
        sr.nextBytes(salt);
        return bytesToHex(salt);
    }

    // 비밀번호 해시화
    // SHA-256 알고리즘을 사용하여 (비밀번호 + Salt)를 복호화 불가능한 문자열로 변환한다.
    private static String getHashedPassword(String password, String salt) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(salt.getBytes());
        byte[] hashedPassword = md.digest(password.getBytes());
        return bytesToHex(hashedPassword);
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // --- [핸들러 클래스] ---
    private static class Handler implements Runnable {
        private String name;
        private Socket socket;
        private Scanner in;
        private PrintWriter out;

        public Handler(Socket socket) {
            this.socket = socket;
        }

        // 귓속말 명령어가 처리되는 핵심 부분
        // Client 1명과 1:1로 통신하는 스레드의 메인 로직이다.
        // 크게 3단계로 나뉜다 : (1)인증 -> (2)입장 -> (3)메세지 루프
        public void run() {
            try {
                in = new Scanner(socket.getInputStream());
                out = new PrintWriter(socket.getOutputStream(), true);

                // 1. 인증 단계
                // 로그인이나 회원가입이 성공할 때까지 무한 반복한다.
                while (true) {
                    out.println("SUBMITNAME"); // 클라이언트에게 "입력하세요"라고 요청한다.
                    if (!in.hasNextLine()) return;
                    String command = in.nextLine();

                    if (command.startsWith("REGISTER ")) {
                        // 회원가입 요청 처리이다.
                        String[] parts = command.split(" ", 5);
                        if (parts.length == 5) {
                            if (registerUser(parts[1], parts[2], parts[3], parts[4])) {
                                out.println("REGISTER_SUCCESS");
                            } else {
                                out.println("REGISTER_FAIL ID_EXISTS");
                            }
                        }
                        // 로그인 요청 처리 로직이다
                        // 이미 접속 중인 ID인지 메모리 (activeClients)에서 확인 후
                        // authenticateUser로 파일 대조를 수행한다.
                        // 성공 시 "LOGIN_SUCCESS" 전송 후 break로 루프를 탈출한다.
                    } else if (command.startsWith("LOGIN ")) {
                        String[] parts = command.split(" ", 3);
                        if (parts.length == 3) {
                            String tryID = parts[1];
                            String tryPass = parts[2];

                            synchronized (activeClients) {
                                if (activeClients.containsKey(tryID)) {
                                    out.println("LOGIN_FAIL ALREADY_LOGGED_IN");
                                    continue;
                                }
                            }
                            // authenticateUser로 파일 대조를 수행한다.
                            // 성공 시 "LOGIN_SUCCESS" 전송 후 break로 루프를 탈출한다.
                            if (authenticateUser(tryID, tryPass)) {
                                this.name = tryID;
                                out.println("LOGIN_SUCCESS " + this.name);
                                break;
                            } else {
                                out.println("LOGIN_FAIL WRONG_ID_PW");
                            }
                        }
                    }
                }

                // 2. 입장 처리
                out.println("NAMEACCEPTED " + name);
                synchronized (activeClients) {
                    activeClients.put(name, out);
                }

                broadcast("MESSAGE [공지] " + name + " 님이 입장하셨습니다.");
                broadcastUserList(); // <--- 중요! 접속자 명단 갱신 전송

                // 3. 메시지 처리 루프
                // 여기서부터는 클라이언트가 보내는 말을 계속 받아서 처리한다.
                while (true) {
                    if (!in.hasNextLine()) break; // 연결 끊기면 종료
                    String input = in.nextLine();

                    if (input.toLowerCase().startsWith("/quit")) {
                        break; // 종료 명령
                    }

                    // 귓속말 처리 (/whisper 대상 메시지)
                    if (input.startsWith("/whisper ")) {
                        String[] parts = input.split(" ", 3);
                        if (parts.length == 3) {
                            String targetID = parts[1];
                            String msg = parts[2];
                            sendWhisper(targetID, msg); // 귓속말 함수 호출
                        } else {
                            out.println("MESSAGE [시스템] 귓속말 형식이 잘못되었습니다.");
                        }
                        continue; // 귓속말은 전체 방송 안 하고 여기서 끝냄
                    }

                    // 일반 메시지 -> 모두에게 전송
                    broadcast("MESSAGE " + name + ": " + input);
                }
            } catch (Exception e) {
                System.out.println(e);
            } finally {
                // --- 4. 퇴장 및 뒷정리 ---
                // try 블록 안에서 무슨 일이 있어도 여기는 무조건 실행됨
                if (name != null) {
                    synchronized (activeClients) {
                        activeClients.remove(name); // 명단에서 지우기
                    }
                    broadcast("MESSAGE [공지] " + name + " 님이 퇴장하셨습니다.");
                    broadcastUserList(); // <--- 중요! 나갈 때도 명단 갱신
                }
                try { socket.close(); } catch (IOException e) {} // 전화 끊음
            }
        }

        // 전체 방송 핼퍼
        private void broadcast(String message) {
            synchronized (activeClients) {
                // 접속자 맵의 값(PrintWriter)들만 모아서 하나씩 메세지를 쓴다.
                for (PrintWriter writer : activeClients.values()) {
                    writer.println(message);
                }
            }
        }

        // 귓속말 핼퍼
        private void sendWhisper(String targetID, String message) {
            synchronized (activeClients) {
                // 맵에서 targetID(받는 사람)의 PrintWriter를 찾아낸다.
                PrintWriter targetWriter = activeClients.get(targetID);
                if (targetWriter != null) {
                    // 받는 사람에게 전송
                    targetWriter.println("MESSAGE (귓속말 from " + name + "): " + message);
                    // 보낸 사람에게도 전송 (그래야 내 화면에도 뜸)
                    out.println("MESSAGE (귓속말 to " + targetID + "): " + message);
                } else {
                    out.println("MESSAGE [시스템] '" + targetID + "' 님은 현재 접속 중이 아닙니다.");
                }
            }
        }
    }
}