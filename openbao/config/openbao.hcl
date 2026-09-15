ui = true

# api_addr must be an address clients can actually reach.
# "0.0.0.0" is a bind address, not a routable one - never use it here.
api_addr = "http://127.0.0.1:8200"

listener "tcp" {
  # 0.0.0.0 is correct HERE - this is where the server binds.
  address     = "0.0.0.0:8200"
  tls_disable = true
}

storage "file" {
  # /openbao/file is the path the image declares as a VOLUME and ships
  # owned by openbao:openbao. Mounting a named volume here inherits that
  # ownership, so no manual chown is ever needed.
  #
  # Do NOT use /openbao/data: it does not exist in the image, so Docker
  # creates it as root:root and OpenBao (uid 100) cannot write to it.
  path = "/openbao/file"
}
