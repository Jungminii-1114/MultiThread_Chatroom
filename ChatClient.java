package Chatroom;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/**
 * ChatClient 클래스
 * * [출처 및 기여 명시]
 * 본 소스 코드의 GUI 디자인 및 레이아웃 구현, 이벤트 처리 구조(Swing)와 관련된 부분들 중 일부는
 * AI 모델(GPT)의 도움을 받아 작성 및 개선되었습니다.
 */
public class ChatClient {

    // 서버 연결 정보
    String serverAddress;
    int serverPort;

    // 입출력 도구
    Scanner in;
    PrintWriter out;

    String myName;     // 내 아이디를 기억해둔다. (접속자 목록에서 'myName'에 해당하는 사항은 제외 예정)

    // --- GUI Components (화면 구성요소) ---
    // ※ 이 아래의 GUI 구성 요소 및 배치 로직은 AI의 제안을 바탕으로 작성되었습니다.
    JFrame frame = new JFrame("Chatter"); // 전체 윈도우 창
    JTextField textField = new JTextField(40); // 메시지 입력하는 칸
    JTextArea messageArea = new JTextArea(16, 50); // 채팅 로그가 찍히는 큰 화면

    // 접속자 목록 UI
    // DefaultListModel : 리스트에 데이터를 추가/삭제하기 쉽게 도와주는 모델 객체이다.
    // JList는 View일 뿐, 실제 데이터는 DefaultListModel이 관리한다.
    // 데이터를 추가하거나 삭제할 때에는 Model을 건드리면 JList가 알아서 화면을 바꿔준다.
    DefaultListModel<String> userListModel = new DefaultListModel<>();
    JList<String> userList = new JList<>(userListModel); // 모델을 화면에 보여주는 뷰(View)

    // 귓속말 대상 관리
    // 기본값은 "Everyone"이고, 리스트에서 특정 상대방을 선택하면, 그 사람의 ID값으로 바뀌게 된다.
    private String currentRecipient = "Everyone";
    JLabel targetLabel = new JLabel("To: 전체 (Everyone)");

    // 생성자 : 화면(GUI)을 조립하고 이벤트를 연결하는 곳
    public ChatClient(String serverAddress, int serverPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;

        // 1. UI 스타일 및 레이아웃 설정
        textField.setEditable(false); // 로그인 전에는 채팅 입력 불가
        messageArea.setEditable(false); // 채팅 내역은 사용자가 지우거나 쓸 수 없어야 하니, 읽기 전용으로 한다.
        messageArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        messageArea.setLineWrap(true); // 글자가 길어지면 자동으로 줄바꿈

        // JScrollPane : 내용이 많아지면 스크롤바가 생기게 해준다.
        JScrollPane messageScroll = new JScrollPane(messageArea);
        messageScroll.setBorder(BorderFactory.createTitledBorder("채팅 내용"));

        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); // 한 번에 한 명만 선택 가능
        userList.setFont(new Font("SansSerif", Font.PLAIN, 13));
        JScrollPane listScroll = new JScrollPane(userList);
        listScroll.setPreferredSize(new Dimension(150, 0)); // 목록창의 너비 고정
        listScroll.setBorder(BorderFactory.createTitledBorder("접속자 목록"));

        // 하단 입력 패널 (South)
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5)); // BorderLayout : 동서남북 중앙 배치 관리자
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // 대상 표시 라벨 스타일 (누구한테 보낼지 알려주는 텍스트)
        targetLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        targetLabel.setForeground(Color.DARK_GRAY);
        targetLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));

        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        inputPanel.add(targetLabel, BorderLayout.NORTH); // "To: 전체" 라벨
        inputPanel.add(textField, BorderLayout.CENTER);  // 입력창

        JButton sendButton = new JButton("전송");
        inputPanel.add(sendButton, BorderLayout.EAST);   // 전송 버튼

        bottomPanel.add(inputPanel, BorderLayout.CENTER);

        // 프레임(윈도우)에 조립한 패널들을 배치한다.
        frame.getContentPane().setLayout(new BorderLayout()); // 전체 Window또한 BorderLayout을 사용한다.
        frame.getContentPane().add(messageScroll, BorderLayout.CENTER); // 가운데에 채팅창
        frame.getContentPane().add(listScroll, BorderLayout.EAST); // 오른쪽에 목록
        frame.getContentPane().add(bottomPanel, BorderLayout.SOUTH); // 아래쪽에 입력창
        frame.pack(); // 컴포넌트 크기에 맞춰서 창 크기 자동 조절


        // [1] 접속자 목록 클릭 리스너 (귓속말 대상 변경 로직)
        // 사용자가 오른쪽 목록에서 누군가를 클릭했을 때 실행된다.
        userList.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                // Value Changed는 누르는 순간과 떼는 순간 두번 발생한다.
                // getValueIsAdjusting()이 false일 때만 처리해야 중복 실행을 방지한다.
                if (!e.getValueIsAdjusting()) {
                    String selected = userList.getSelectedValue();
                    // 선택이 없거나 '전체보내기'를 클릭했을 떄
                    if (selected == null || selected.contains("전체보내기")) {
                        currentRecipient = "Everyone"; // 타겟을 'Everyone'으로 설정한다
                        targetLabel.setText("To: 전체 (Everyone)");
                        targetLabel.setForeground(Color.DARK_GRAY);
                    } else {
                        // 특정 사람을 클릭했을 때
                        currentRecipient = selected; // 선택된 사람의 ID로 설정한다.
                        targetLabel.setText("To: " + selected + " (귓속말)");
                        targetLabel.setForeground(Color.MAGENTA);
                    }
                }
            }
        });

        // 엔터키와 전송 버튼 클릭시 메세지를 보내는 과정이다.
        ActionListener sendAction = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String msg = textField.getText();
                if (msg.isEmpty()) return; // 빈 메시지는 보내지 않는다.

                // 현재 설정된 대상(currentRecipient)에 따라 보내는 명령어가 달라진다.
                if (currentRecipient.equalsIgnoreCase("Everyone")) {
                    out.println(msg); // 그냥 보내면 서버가 알아서 전체에게 뿌린다.
                } else {
                    // "/whisper 대상 메시지" 형태로 만들어서 서버에 전송한다.
                    out.println("/whisper " + currentRecipient + " " + msg);
                }
                textField.setText(""); // 입력창 비우기
                textField.requestFocus(); // 다시 입력창에 커서 두기
            }
        };
        // 입력창에서 엔터 쳤을 때, 전송 버튼 눌렀을 때 둘 다 sendAction을 실행한다.
        textField.addActionListener(sendAction);
        sendButton.addActionListener(sendAction);
    }

    // 프로그램 시작 시 로그인/회원가입/종료를 묻는 첫 번째 관문이다.
    private String getAuthenticationCommand() {
        Object[] options = {"로그인", "회원가입", "종료"};
        int choice = JOptionPane.showOptionDialog(frame,
                "환영합니다! 접속 정보를 입력하세요.", "Chatter 인증",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, options, options[0]);

        if (choice == 0) return showLoginDialog();
        else if (choice == 1) return showRegisterDialog();
        else { System.exit(0); return null; }
    }

    // 로그인 입력창을 띄우고, 사용자가 입력한 정보를 "LOGIN 아이디 비번" 문자열로 만든다.
    private String showLoginDialog() {
        JTextField idField = new JTextField();
        JPasswordField passField = new JPasswordField();
        Object[] message = {"아이디:", idField, "비밀번호:", passField};
        int option = JOptionPane.showConfirmDialog(frame, message, "로그인", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {
            return "LOGIN " + idField.getText().trim() + " " + new String(passField.getPassword()).trim();
        }
        return "";
    }

    private String showRegisterDialog() {
        JTextField idField = new JTextField();
        JPasswordField passField = new JPasswordField();
        JTextField nameField = new JTextField();
        JTextField emailField = new JTextField();
        Object[] message = {"아이디:", idField, "비밀번호:", passField, "이름:", nameField, "이메일:", emailField};
        int option = JOptionPane.showConfirmDialog(frame, message, "회원가입", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {
            return "REGISTER " + idField.getText().trim() + " " + new String(passField.getPassword()).trim() +
                    " " + nameField.getText().trim() + " " + emailField.getText().trim();
        }
        return "";
    }

    private void run() throws IOException {
        try {
            Socket socket = new Socket(serverAddress, serverPort);
            in = new Scanner(socket.getInputStream());
            out = new PrintWriter(socket.getOutputStream(), true);

            while (in.hasNextLine()) {
                String line = in.nextLine();

                // 1. 접속자 명단 업데이트 프로토콜 (/userlist id1,id2,...)
                // 서버가 "누가 접속해 있는지" 알려주면 GUI 목록을 갱신해야 한다.
                if (line.startsWith("/userlist ")) {
                    String[] users = line.substring(10).split(",");

                    // 네트워크 스레드(지금 이 곳)에서 직접 GUI(Swing)를 건드리면 프로그램이 멈출 수 있다.
                    // 그래서 "SwingUtilities.invokeLater"를 써서 GUI 전용 스레드에게 작업을 부탁해야 한다.
                    SwingUtilities.invokeLater(() -> {
                        String currentSelection = userList.getSelectedValue();
                        userListModel.clear();
                        userListModel.addElement(" [ 전체보내기 ] ");
                        for (String user : users) {
                            if (!user.isEmpty() && !user.equals(myName)) {
                                userListModel.addElement(user);
                            }
                        }
                        if (currentSelection != null && userListModel.contains(currentSelection)) {
                            userList.setSelectedValue(currentSelection, true);
                        }
                    });
                }
                // 2. 인증 요청 (서버가 "이름 대세요" 함)
                else if (line.startsWith("SUBMITNAME")) {
                    String cmd = getAuthenticationCommand(); // 팝업창 띄워서 입력받음
                    if (cmd != null && !cmd.isEmpty()) out.println(cmd); // 서버로 전송
                }
                // 3. 로그인 성공
                else if (line.startsWith("LOGIN_SUCCESS")) {
                    myName = line.substring(14); // "LOGIN_SUCCESS 아이디" 에서 아이디만 잘라냄
                    frame.setTitle("Chatter - 접속자: " + myName);
                    textField.setEditable(true);
                    messageArea.append("=== 로그인 성공! 우측 목록에서 대상을 선택하여 귓속말을 할 수 있습니다. ===\n");
                }
                // 4. 기타 메시지 처리 (시스템 메시지, 채팅 등)
                else if (line.startsWith("LOGIN_FAIL")) {
                    JOptionPane.showMessageDialog(frame, "로그인 실패: " + line.substring(11));
                } else if (line.startsWith("REGISTER_SUCCESS")) {
                    JOptionPane.showMessageDialog(frame, "회원가입 성공! 로그인해주세요.");
                } else if (line.startsWith("REGISTER_FAIL")) {
                    JOptionPane.showMessageDialog(frame, "회원가입 실패: " + line.substring(14));
                } else if (line.startsWith("MESSAGE")) {
                    // 실제 채팅 메시지가 오면 화면에 보여준다.
                    messageArea.append(line.substring(8) + "\n");
                    // 스크롤을 항상 맨 아래(최신 메시지)로 내린다.
                    messageArea.setCaretPosition(messageArea.getDocument().getLength());
                }
            }
        } finally {
            // 루프를 빠져나오면 (서버 연결 끊김 등) 창을 닫는다.
            frame.setVisible(false);
            frame.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        // Nimbus Look and Feel 적용
        // 자바 기본 GUI는 좀 투박해서, 좀 더 세련된 'Nimbus' 스타일을 입힌다.
        // (이 부분-> 디자인 개선의 일환으로 AI가 제안함)
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception e) {
            // Nimbus가 없으면 그냥 기본 스타일로 간다.
        }

        // 설정 파일(server_info2.dat) 로드 로직
        // 매번 코드를 수정하지 않고도 접속할 서버 IP를 파일로 관리하기 위함이다.
        String ip = "127.0.0.1"; // 기본값 (내 컴퓨터)
        int port = 59001;        // 기본값
        File file = new File("server_info2.dat");
        if (file.exists() && !file.isDirectory()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String lineIP = br.readLine();
                String linePort = br.readLine();
                if (lineIP != null) ip = lineIP.trim();
                if (linePort != null) port = Integer.parseInt(linePort.trim());
            } catch (Exception e) {}
        }

        // 클라이언트 객체 생성 및 화면 표시
        ChatClient client = new ChatClient(ip, port);
        client.frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // X 버튼 누르면 프로그램 종료
        client.frame.setVisible(true); // 창 띄우기
        client.run(); // 통신 시작!
    }
}