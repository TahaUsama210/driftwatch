package checks

// Catalog returns the phase 1 check catalog in a stable order. IDs, titles,
// categories and severities are fixed by docs/CONTRACTS.md so that the API and
// the dashboard agree on them; change them only together with the contract.
func Catalog() []Check {
	return []Check{
		{"ssh.permit_root_login", "SSH root login is disabled", CategorySSH, SeverityHigh, permitRootLogin.evaluate},
		{"ssh.password_authentication", "SSH password authentication is disabled", CategorySSH, SeverityHigh, passwordAuthentication.evaluate},
		{"ssh.permit_empty_passwords", "SSH rejects empty passwords", CategorySSH, SeverityCritical, permitEmptyPasswords.evaluate},
		{"ssh.max_auth_tries", "SSH limits authentication attempts", CategorySSH, SeverityMedium, maxAuthTries.evaluate},
		{"ssh.x11_forwarding", "SSH X11 forwarding is disabled", CategorySSH, SeverityLow, x11Forwarding.evaluate},
		{"ssh.login_grace_time", "SSH login grace time is 60s or less", CategorySSH, SeverityLow, loginGraceTime.evaluate},
		{"ssh.idle_timeout", "SSH idle sessions time out", CategorySSH, SeverityLow, clientAliveInterval.evaluate},
		{"ssh.config_permissions", "sshd_config is owned by root and not group/world accessible", CategorySSH, SeverityMedium, sshConfigPermissions},

		{"accounts.shadow_permissions", "/etc/shadow is protected", CategoryAccounts, SeverityHigh, shadowPermissions},
		{"accounts.passwd_permissions", "/etc/passwd is not writable by group or others", CategoryAccounts, SeverityMedium, passwdPermissions},
		{"accounts.uid0_only_root", "Only root has UID 0", CategoryAccounts, SeverityCritical, uid0OnlyRoot},
		{"accounts.no_empty_passwords", "No account has an empty password", CategoryAccounts, SeverityCritical, noEmptyPasswords},
		{"accounts.pass_max_days", "Passwords expire within 365 days", CategoryAccounts, SeverityMedium, passwordAging{key: "PASS_MAX_DAYS", min: 1, max: 365}.evaluate},
		{"accounts.pass_min_days", "Passwords have a minimum age", CategoryAccounts, SeverityLow, passwordAging{key: "PASS_MIN_DAYS", min: 1}.evaluate},
		{"accounts.pass_warn_age", "Users are warned before password expiry", CategoryAccounts, SeverityLow, passwordAging{key: "PASS_WARN_AGE", min: 7}.evaluate},

		{"network.ip_forwarding_disabled", "IPv4 forwarding is disabled", CategoryNetwork, SeverityMedium, sysctl{"net.ipv4.ip_forward", 0}.evaluate},
		{"network.icmp_redirects_rejected", "ICMP redirects are not accepted", CategoryNetwork, SeverityMedium, sysctl{"net.ipv4.conf.all.accept_redirects", 0}.evaluate},
		{"network.source_routing_rejected", "Source-routed packets are not accepted", CategoryNetwork, SeverityMedium, sysctl{"net.ipv4.conf.all.accept_source_route", 0}.evaluate},
		{"network.syn_cookies_enabled", "TCP SYN cookies are enabled", CategoryNetwork, SeverityMedium, sysctl{"net.ipv4.tcp_syncookies", 1}.evaluate},

		{"kernel.aslr_enabled", "Full address space layout randomization is enabled", CategoryKernel, SeverityHigh, sysctl{"kernel.randomize_va_space", 2}.evaluate},

		{"filesystem.crontab_permissions", "/etc/crontab is owned by root and private", CategoryFilesystem, SeverityLow, crontabPermissions},
	}
}
