import { UserManager } from 'oidc-client-ts';

/**
 * Entry point of /silent-renew.html, which Keycloak redirects to inside the hidden
 * iframe that oidc-client-ts uses for silent sign-in. It only hands the callback URL to
 * the parent window with postMessage (target origin: this origin), where the parent's
 * UserManager validates and redeems it. That step reads no client settings, so the
 * values below are placeholders rather than a second copy of /config.json.
 */
void new UserManager({
  authority: window.location.origin,
  client_id: 'silent-renew',
  redirect_uri: window.location.href,
  automaticSilentRenew: false,
})
  .signinSilentCallback()
  .catch((error: unknown) => {
    console.error('Driftwatch: silent sign-in callback failed.', error);
  });
