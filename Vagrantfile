# -*- mode: ruby -*-
# vi: set ft=ruby :

# Vagrantfile API/syntax version. Don't touch unless you know what you're doing!
VAGRANTFILE_API_VERSION = "2"

Vagrant.configure(VAGRANTFILE_API_VERSION) do |config|
  config.vm.box = "ubuntu/trusty64"

  config.vm.define "demo", autostart: false do |demo|
    demo.vm.network "private_network", ip: "192.168.50.5"
    demo.vm.hostname = "aurum-demo.mit.edu"
    demo.vm.provision "shell", path: "provisioning/provision-vagrant-demo-server.sh"
      config.vm.provider "virtualbox" do |vm|
      vm.name = "aurum-demo"
      vm.memory = 2048
      #vm.cpus = 2
      # Set the timesync threshold to 1 minute, instead of the default 20 minutes.
      vm.customize ["guestproperty", "set", :id, "/VirtualBox/GuestAdd/VBoxService/--timesync-set-threshold", 60000]
    end
  end
end
