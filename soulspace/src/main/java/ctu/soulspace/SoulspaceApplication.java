package ctu.soulspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Cho phép Spring tự gọi các hàm @Scheduled khi backend đang chạy.
@EnableScheduling
public class SoulspaceApplication {

	public static void main(String[] args) {
		SpringApplication.run(SoulspaceApplication.class, args);
	}

}
